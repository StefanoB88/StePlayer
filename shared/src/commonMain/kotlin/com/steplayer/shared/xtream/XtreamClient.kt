package com.steplayer.shared.xtream

import com.steplayer.shared.model.AccountInfo
import com.steplayer.shared.model.EpgProgram
import com.steplayer.shared.util.CivilTime
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlin.io.encoding.Base64

class XtreamException(message: String, val kind: Kind, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { INVALID_CREDENTIALS, ACCOUNT_EXPIRED, NETWORK, SERVER, PARSE }
}

internal val XtreamJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/** Result of a successful login. */
internal data class XtreamLogin(
    val account: AccountInfo,
    /** Seconds to add to UTC to get the server's local time (needed for catch-up). */
    val serverOffsetSec: Long,
    /** Base URL as advertised by the server (may switch to https). */
    val baseUrl: String,
)

/**
 * Thin client over the Xtream-compatible `player_api.php` API.
 * All network errors are mapped to [XtreamException] with a user-presentable [XtreamException.Kind].
 */
internal class XtreamClient(
    private val http: HttpClient,
    private val creds: XtreamCredentials,
    private val userAgent: String?,
) {
    /**
     * Runs [block] on the streamed response (the body is NOT buffered in memory first, unlike
     * `http.get`). Network failures — also while reading the body — become [XtreamException].
     */
    private suspend fun <R> call(
        action: String?,
        vararg params: Pair<String, String>,
        block: suspend (HttpResponse) -> R,
    ): R = try {
        http.prepareGet("${creds.baseUrl}/player_api.php") {
            parameter("username", creds.username)
            parameter("password", creds.password)
            if (action != null) parameter("action", action)
            params.forEach { (k, v) -> parameter(k, v) }
            userAgent?.let { header(HttpHeaders.UserAgent, it) }
        }.execute { response ->
            if (response.status.value == 401 || response.status.value == 403) {
                throw XtreamException("Credenziali non valide", XtreamException.Kind.INVALID_CREDENTIALS)
            }
            if (!response.status.isSuccess()) {
                throw XtreamException("Errore del server (${response.status.value})", XtreamException.Kind.SERVER)
            }
            block(response)
        }
    } catch (e: XtreamException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw XtreamException("Impossibile contattare il server: ${e.message ?: e::class.simpleName}", XtreamException.Kind.NETWORK, e)
    }

    private suspend fun <T> decode(action: String?, strategy: DeserializationStrategy<T>, vararg params: Pair<String, String>): T {
        val text = call(action, *params) { it.bodyAsText() }
        return try {
            XtreamJson.decodeFromString(strategy, text)
        } catch (e: Exception) {
            throw XtreamException("Risposta del server non valida", XtreamException.Kind.PARSE, e)
        }
    }

    /**
     * Lists may come back as `[]`, `{}` (empty), or `null` on some servers.
     *
     * Decoded in streaming, one array element at a time, off the caller's thread: a 50k-title
     * catalog never exists in memory as one big body/String next to the decoded list (OOM risk
     * on low-end TV boxes).
     */
    private suspend fun <T> decodeList(action: String, item: KSerializer<T>): List<T> = call(action) { response ->
        val channel = response.bodyAsChannel()
        withContext(Dispatchers.Default) {
            val out = ArrayList<T>()
            val splitter = JsonArraySplitter { bytes, len ->
                out += try {
                    XtreamJson.decodeFromString(item, bytes.decodeToString(0, len))
                } catch (e: Exception) {
                    throw XtreamException("Risposta del server non valida ($action)", XtreamException.Kind.PARSE, e)
                }
            }
            val buffer = ByteArray(64 * 1024)
            while (!splitter.isDone) {
                val n = channel.readAvailable(buffer, 0, buffer.size)
                if (n < 0) break
                if (n > 0) splitter.feed(buffer, n)
            }
            try {
                splitter.finish()
            } catch (e: IllegalStateException) {
                throw XtreamException("Risposta del server non valida ($action)", XtreamException.Kind.PARSE, e)
            }
            out
        }
    }

    suspend fun login(): XtreamLogin {
        val dto = decode(null, AuthResponseDto.serializer())
        val user = dto.userInfo
        if (user == null || user.auth == false) {
            throw XtreamException("Credenziali non valide", XtreamException.Kind.INVALID_CREDENTIALS)
        }
        val status = user.status
        if (status != null && status.equals("Expired", ignoreCase = true)) {
            throw XtreamException("L'abbonamento è scaduto", XtreamException.Kind.ACCOUNT_EXPIRED)
        }
        val server = dto.serverInfo
        val offset = computeServerOffset(server?.timeNow, server?.timestampNow)
        val formats = when (val f = user.allowedOutputFormats) {
            is JsonArray -> f.mapNotNull { it.flexString() }
            else -> emptyList()
        }
        return XtreamLogin(
            account = AccountInfo(
                status = status,
                expiresAt = user.expDate?.takeIf { it > 0 }?.let { it * 1000 },
                maxConnections = user.maxConnections,
                activeConnections = user.activeCons,
                isTrial = user.isTrial == true,
                allowedFormats = formats,
            ),
            serverOffsetSec = offset,
            baseUrl = creds.baseUrl,
        )
    }

    suspend fun liveCategories() = decodeList("get_live_categories", CategoryDto.serializer())
    suspend fun vodCategories() = decodeList("get_vod_categories", CategoryDto.serializer())
    suspend fun seriesCategories() = decodeList("get_series_categories", CategoryDto.serializer())
    suspend fun liveStreams() = decodeList("get_live_streams", LiveStreamDto.serializer())
    suspend fun vodStreams() = decodeList("get_vod_streams", VodStreamDto.serializer())
    suspend fun series() = decodeList("get_series", SeriesDto.serializer())

    suspend fun vodInfo(vodId: String): JsonElement =
        decode("get_vod_info", JsonElement.serializer(), "vod_id" to vodId)

    suspend fun seriesInfo(seriesId: String): JsonElement =
        decode("get_series_info", JsonElement.serializer(), "series_id" to seriesId)

    /** Full EPG of one channel (titles/descriptions are base64 encoded by the server). */
    suspend fun channelEpg(streamId: String, epgChannelId: String): List<EpgProgram> {
        val root = decode("get_simple_data_table", JsonElement.serializer(), "stream_id" to streamId)
        val listings = (root["epg_listings"] as? JsonArray) ?: return emptyList()
        return listings.mapNotNull { item ->
            val start = item["start_timestamp"].flexLong()?.times(1000) ?: return@mapNotNull null
            val end = item["stop_timestamp"].flexLong()?.times(1000) ?: return@mapNotNull null
            EpgProgram(
                channelEpgId = epgChannelId,
                startAt = start,
                endAt = end,
                title = decodeBase64(item["title"].flexString()) ?: return@mapNotNull null,
                description = decodeBase64(item["description"].flexString()),
            )
        }
    }

    companion object {
        /** Servers report "time_now" in their local zone and "timestamp_now" in UTC. */
        fun computeServerOffset(timeNow: String?, timestampNow: Long?): Long {
            if (timeNow == null || timestampNow == null) return 0
            val localAsUtcMs = CivilTime.parseSqlDateTime(timeNow) ?: return 0
            val diff = localAsUtcMs / 1000 - timestampNow
            // Round to the nearest quarter of an hour to absorb request latency.
            return ((diff + if (diff >= 0) 450 else -450) / 900) * 900
        }

        fun decodeBase64(value: String?): String? {
            if (value.isNullOrBlank()) return null
            return runCatching { Base64.decode(value.trim()).decodeToString() }.getOrDefault(value)
                .trim().takeIf { it.isNotEmpty() }
        }
    }
}

/** Helpers to read the loosely typed info objects of get_vod_info / get_series_info. */
internal object XtreamInfoParser {
    fun backdrop(info: JsonElement?): String? = when (val b = info["backdrop_path"]) {
        is JsonArray -> b.firstOrNull().flexString()
        else -> b.flexString()
    }

    fun durationSec(info: JsonElement?): Int? =
        info["duration_secs"].flexInt() ?: info["duration"].flexString()?.let { parseHms(it) }

    private fun parseHms(s: String): Int? {
        val p = s.split(':').map { it.trim().toLongOrNull() ?: return null }
        val total = when (p.size) {
            3 -> p[0] * 3600 + p[1] * 60 + p[2]
            2 -> p[0] * 60 + p[1]
            else -> return null
        }
        return total.takeIf { it in 0..Int.MAX_VALUE }?.toInt()
    }

    /** episodes may be `{"1":[...],"2":[...]}` or `[[...],[...]]` or a flat list. */
    fun episodes(root: JsonElement): List<JsonElement> = when (val e = root["episodes"]) {
        is JsonObject -> e.values.flatMap { (it as? JsonArray)?.toList() ?: emptyList() }
        is JsonArray -> e.flatMap { if (it is JsonArray) it.jsonArray.toList() else listOf(it) }
        else -> emptyList()
    }
}

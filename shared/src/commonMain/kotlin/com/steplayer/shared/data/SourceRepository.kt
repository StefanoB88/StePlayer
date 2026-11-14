package com.steplayer.shared.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.steplayer.shared.db.StePlayerDatabase
import com.steplayer.shared.model.Source
import com.steplayer.shared.model.SourceInput
import com.steplayer.shared.model.SourceType
import com.steplayer.shared.platform.CredentialStore
import com.steplayer.shared.search.SearchIndex
import com.steplayer.shared.util.CFlow
import com.steplayer.shared.util.nowMillis
import com.steplayer.shared.util.wrap
import com.steplayer.shared.xtream.XtreamClient
import com.steplayer.shared.xtream.XtreamCredentials
import com.steplayer.shared.xtream.XtreamUrls
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class SourceRepository internal constructor(
    private val db: StePlayerDatabase,
    private val credentials: CredentialStore,
    private val http: HttpClient,
    private val searchIndex: SearchIndex,
) {
    private val q get() = db.schemaQueries

    val sources: CFlow<List<Source>> =
        q.allSources().asFlow().mapToList(Dispatchers.IO).map { list -> list.map { it.toModel() } }.wrap()

    private val activeId: Flow<Long?> =
        q.getSetting(KEY_ACTIVE).asFlow().mapToOneOrNull(Dispatchers.IO).map { it?.toLongOrNull() }

    /** The currently selected list (falls back to the first one). */
    val activeSource: CFlow<Source> = combine(sources, activeId) { list, id ->
        list.firstOrNull { it.id == id } ?: list.firstOrNull() ?: NO_SOURCE
    }.wrap()

    @Throws(Throwable::class)
    suspend fun current(): Source? = withContext(Dispatchers.IO) {
        val id = q.getSetting(KEY_ACTIVE).executeAsOneOrNull()?.toLongOrNull()
        val all = q.allSources().executeAsList()
        (all.firstOrNull { it.id == id } ?: all.firstOrNull())?.toModel()
    }

    @Throws(Throwable::class)
    suspend fun byId(id: Long): Source? = withContext(Dispatchers.IO) {
        q.sourceById(id).executeAsOneOrNull()?.toModel()
    }

    @Throws(Throwable::class)
    suspend fun setActive(sourceId: Long) = withContext(Dispatchers.IO) {
        q.putSetting(KEY_ACTIVE, sourceId.toString())
    }

    /**
     * Validates the input against the server (for Xtream) and stores it.
     * If an M3U link is actually an Xtream "get.php" link, it is upgraded to Xtream automatically.
     * @return the new source id.
     */
    @Throws(Throwable::class)
    suspend fun add(input: SourceInput): Long {
        val normalized = upgradeIfXtream(input.withSchemes())
        if (normalized is SourceInput.Xtream) {
            // Throws XtreamException with a readable message if login fails.
            val creds = XtreamCredentials(XtreamUrls.normalizeBaseUrl(normalized.serverUrl), normalized.username.trim(), normalized.password)
            XtreamClient(http, creds, normalized.userAgent).login()
        } else if (normalized is SourceInput.M3u) {
            require(normalized.listUrl.trim().contains("://")) { "URL della lista non valido" }
        }
        return withContext(Dispatchers.IO) {
            db.transactionWithResult {
                when (normalized) {
                    is SourceInput.Xtream -> q.insertSource(
                        name = normalized.name.ifBlank { "Xtream" },
                        type = SourceType.XTREAM.name,
                        url = XtreamUrls.normalizeBaseUrl(normalized.serverUrl),
                        username = normalized.username.trim(),
                        epg_url = null,
                        user_agent = normalized.userAgent?.takeIf { it.isNotBlank() },
                        created_at = nowMillis(),
                    )
                    is SourceInput.M3u -> q.insertSource(
                        name = normalized.name.ifBlank { "Lista" },
                        type = SourceType.M3U.name,
                        // The full URL may contain tokens: keep it in secure storage, store a redacted label.
                        url = labelForM3u(normalized.listUrl),
                        username = null,
                        epg_url = normalized.epgUrl?.takeIf { it.isNotBlank() },
                        user_agent = normalized.userAgent?.takeIf { it.isNotBlank() },
                        created_at = nowMillis(),
                    )
                }
                val id = q.lastInsertedId().executeAsOne()
                when (normalized) {
                    is SourceInput.Xtream -> credentials.save(secretKey(id), normalized.password)
                    is SourceInput.M3u -> credentials.save(secretKey(id), normalized.listUrl.trim())
                }
                q.putSetting(KEY_ACTIVE, id.toString())
                id
            }
        }
    }

    @Throws(Throwable::class)
    suspend fun update(id: Long, input: SourceInput) {
        val existing = byId(id) ?: return
        val normalized = input.withSchemes()
        withContext(Dispatchers.IO) {
            when (normalized) {
                is SourceInput.Xtream -> {
                    q.updateSource(normalized.name, XtreamUrls.normalizeBaseUrl(normalized.serverUrl), normalized.username.trim(), existing.epgUrl, normalized.userAgent, id)
                    if (normalized.password.isNotEmpty()) credentials.save(secretKey(id), normalized.password)
                }
                is SourceInput.M3u -> {
                    q.updateSource(normalized.name, labelForM3u(normalized.listUrl), null, normalized.epgUrl, normalized.userAgent, id)
                    if (normalized.listUrl.isNotBlank()) credentials.save(secretKey(id), normalized.listUrl.trim())
                }
            }
        }
    }

    @Throws(Throwable::class)
    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        db.transaction {
            q.deleteSource(id) // cascades to every catalog table
            searchIndex.drop(id)
            if (q.getSetting(KEY_ACTIVE).executeAsOneOrNull() == id.toString()) q.deleteSetting(KEY_ACTIVE)
        }
        credentials.delete(secretKey(id))
    }

    internal fun xtreamCredentials(source: Source): XtreamCredentials? {
        if (source.type != SourceType.XTREAM) return null
        val password = credentials.load(secretKey(source.id)) ?: return null
        return XtreamCredentials(source.url, source.username.orEmpty(), password)
    }

    internal fun m3uUrl(source: Source): String? =
        if (source.type == SourceType.M3U) credentials.load(secretKey(source.id)) else null

    /** "server.esempio/lista.m3u" works like the Xtream server field: a missing scheme means http. */
    private fun SourceInput.withSchemes(): SourceInput =
        if (this is SourceInput.M3u) copy(listUrl = withScheme(listUrl), epgUrl = epgUrl?.let(::withScheme)) else this

    private fun withScheme(url: String): String {
        val t = url.trim()
        val host = t.substringBefore('/')
        // Only when it looks like an address ("server.esempio/…", "10.0.0.2:8080/…"); anything else
        // is left alone so validation still says the URL is not valid.
        val looksLikeHost = host.isNotEmpty() && (host.contains('.') || host.contains(':')) && host.none { it.isWhitespace() }
        return if (t.isEmpty() || t.contains("://") || !looksLikeHost) t else "http://$t"
    }

    private fun upgradeIfXtream(input: SourceInput): SourceInput {
        if (input !is SourceInput.M3u) return input
        val detected = XtreamUrls.detectFromM3uUrl(input.listUrl) ?: return input
        return SourceInput.Xtream(input.name, detected.baseUrl, detected.username, detected.password, input.userAgent)
    }

    private fun labelForM3u(url: String): String = runCatching {
        val u = io.ktor.http.Url(url.trim())
        "${u.protocol.name}://${u.host}${u.encodedPath}"
    }.getOrDefault("lista")

    companion object {
        private const val KEY_ACTIVE = "active_source_id"
        internal fun secretKey(id: Long) = "source.$id.secret"

        /** Sentinel emitted when no list exists yet (id = -1). */
        val NO_SOURCE = Source(-1, "", SourceType.M3U, "", null, null, null, 0, emptyList(), null, null, null, null)
    }
}

package com.steplayer.shared.xtream

import com.steplayer.shared.util.CivilTime
import com.steplayer.shared.util.pad2
import io.ktor.http.Url
import io.ktor.http.encodeURLPathPart
import io.ktor.http.encodeURLParameter

/** Credentials + server for building Xtream URLs. Never persisted as a whole. */
data class XtreamCredentials(val baseUrl: String, val username: String, val password: String)

object XtreamUrls {

    /**
     * Normalises what users paste: "host:8080", "http://host:8080/", ".../player_api.php?...",
     * ".../get.php?username=..&password=..". Returns "scheme://host[:port]".
     */
    fun normalizeBaseUrl(input: String): String {
        var s = input.trim()
        if (!s.contains("://")) s = "http://$s"
        return runCatching {
            val url = Url(s)
            val defaultPort = url.protocol.defaultPort
            val port = if (url.specifiedPort == 0 || url.port == defaultPort) "" else ":${url.port}"
            "${url.protocol.name}://${url.host}$port"
        }.getOrElse { s.trimEnd('/') }
    }

    /**
     * Detects an Xtream "get.php" M3U link and extracts server + credentials, so that the user
     * gets the much richer Xtream experience even when pasting an M3U link.
     */
    fun detectFromM3uUrl(m3uUrl: String): XtreamCredentials? = runCatching {
        val url = Url(m3uUrl.trim())
        if (!url.encodedPath.endsWith("get.php")) return null
        val user = url.parameters["username"]?.takeIf { it.isNotBlank() } ?: return null
        val pass = url.parameters["password"]?.takeIf { it.isNotBlank() } ?: return null
        XtreamCredentials(normalizeBaseUrl(m3uUrl), user, pass)
    }.getOrNull()

    private fun seg(value: String) = value.encodeURLPathPart()

    fun live(c: XtreamCredentials, streamId: String, ext: String) =
        "${c.baseUrl}/live/${seg(c.username)}/${seg(c.password)}/$streamId.$ext"

    fun movie(c: XtreamCredentials, streamId: String, ext: String?) =
        "${c.baseUrl}/movie/${seg(c.username)}/${seg(c.password)}/$streamId.${ext ?: "mp4"}"

    fun episode(c: XtreamCredentials, episodeId: String, ext: String?) =
        "${c.baseUrl}/series/${seg(c.username)}/${seg(c.password)}/$episodeId.${ext ?: "mp4"}"

    /**
     * Catch-up (timeshift) URL. The start time must be expressed in the *server's* local time,
     * hence [serverOffsetSec] (computed at login from server_info).
     */
    fun timeshift(
        c: XtreamCredentials,
        streamId: String,
        startEpochMs: Long,
        durationMin: Int,
        serverOffsetSec: Long,
    ): String {
        val t = CivilTime.fromEpochSeconds(startEpochMs / 1000, serverOffsetSec)
        val start = "${t.year}-${t.month.pad2()}-${t.day.pad2()}:${t.hour.pad2()}-${t.minute.pad2()}"
        return "${c.baseUrl}/timeshift/${seg(c.username)}/${seg(c.password)}/$durationMin/$start/$streamId.ts"
    }

    fun xmltv(c: XtreamCredentials) = "${c.baseUrl}/xmltv.php?username=${q(c.username)}&password=${q(c.password)}"

    private fun q(v: String) = v.encodeURLParameter()
}

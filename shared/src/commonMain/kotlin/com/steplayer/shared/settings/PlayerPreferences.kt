package com.steplayer.shared.settings

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.steplayer.shared.db.StePlayerDatabase
import com.steplayer.shared.util.CFlow
import com.steplayer.shared.util.wrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class BufferMode { AUTO, NORMAL, LARGE }
enum class SubtitleMode { OFF, AUTO, ALWAYS }
enum class SubtitleColor { WHITE, YELLOW }
enum class SubtitleBackground { NONE, SEMI_TRANSPARENT, OPAQUE }
enum class SubtitleEdge { NONE, OUTLINE, SHADOW }

@Serializable
data class SubtitleStyle(
    /** 0.75, 1.0, 1.25, 1.5, 2.0 */
    val scale: Float = 1.0f,
    val color: SubtitleColor = SubtitleColor.WHITE,
    val background: SubtitleBackground = SubtitleBackground.NONE,
    val edge: SubtitleEdge = SubtitleEdge.OUTLINE,
    /** Distance from the bottom, percent of the video height. */
    val bottomMarginPercent: Int = 8,
)

@Serializable
data class PlayerSettings(
    /** "Stai ancora guardando?" after this many hours without interaction. 0 = never. */
    val stillWatchingAfterHours: Int = 4,
    /** Default: large buffer (fewer stutters on IPTV servers; slightly slower channel start). */
    val bufferMode: BufferMode = BufferMode.LARGE,
    /** ISO 639-1 codes in priority order. Empty = follow the device languages. */
    val preferredAudioLanguages: List<String> = emptyList(),
    /** ISO 639-1 code. Null = the first device language. */
    val preferredSubtitleLanguage: String? = null,
    val subtitleMode: SubtitleMode = SubtitleMode.AUTO,
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    val showStatsOverlay: Boolean = false,
)

/**
 * Player preferences shared by every platform, plus per-content A/V sync offsets
 * (lip-sync problems are usually specific to one channel, so they're remembered per channel).
 */
class PlayerPreferences internal constructor(private val db: StePlayerDatabase) {
    private val q get() = db.schemaQueries
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val settings: CFlow<PlayerSettings> = q.getSetting(KEY).asFlow().mapToOneOrNull(Dispatchers.IO)
        .map { migrate(); decode(it) }.flowOn(Dispatchers.IO).wrap() // migrate() touches the DB

    @Throws(Throwable::class)
    suspend fun current(): PlayerSettings = withContext(Dispatchers.IO) { migrate(); decode(q.getSetting(KEY).executeAsOneOrNull()) }

    /**
     * One-time: settings saved before "Grande" became the default stored AUTO only because it was
     * the old default; move them to LARGE once. Later explicit choices are respected.
     */
    private fun migrate() {
        if (q.getSetting(KEY_MIGRATED).executeAsOneOrNull() != null) return
        db.transaction {
            q.getSetting(KEY).executeAsOneOrNull()?.let { raw ->
                val s = decode(raw)
                if (s.bufferMode == BufferMode.AUTO) {
                    q.putSetting(KEY, json.encodeToString(PlayerSettings.serializer(), s.copy(bufferMode = BufferMode.LARGE)))
                }
            }
            q.putSetting(KEY_MIGRATED, "1")
        }
    }

    @Throws(Throwable::class)
    suspend fun update(transform: (PlayerSettings) -> PlayerSettings) = withContext(Dispatchers.IO) {
        db.transaction {
            val updated = transform(decode(q.getSetting(KEY).executeAsOneOrNull()))
            q.putSetting(KEY, json.encodeToString(PlayerSettings.serializer(), updated))
        }
    }

    /** Swift-friendly setter (closures with generics are awkward from Swift). */
    @Throws(Throwable::class)
    suspend fun save(settings: PlayerSettings) = update { settings }

    /** Audio delay in ms for a channel/movie/episode (positive = audio later). Range ±3000. */
    @Throws(Throwable::class)
    suspend fun audioDelayMs(contentKey: String): Long = delay("audio", contentKey)
    @Throws(Throwable::class)
    suspend fun setAudioDelayMs(contentKey: String, ms: Long) = setDelay("audio", contentKey, ms)

    /** Subtitle delay in ms (positive = subtitles later). Range ±10000. */
    @Throws(Throwable::class)
    suspend fun subtitleDelayMs(contentKey: String): Long = delay("sub", contentKey)
    @Throws(Throwable::class)
    suspend fun setSubtitleDelayMs(contentKey: String, ms: Long) = setDelay("sub", contentKey, ms)

    private suspend fun delay(kind: String, key: String): Long = withContext(Dispatchers.IO) {
        q.getSetting("delay.$kind.$key").executeAsOneOrNull()?.toLongOrNull() ?: 0L
    }

    private suspend fun setDelay(kind: String, key: String, ms: Long) = withContext(Dispatchers.IO) {
        val limit = if (kind == "audio") 3_000L else 10_000L
        val v = ms.coerceIn(-limit, limit)
        if (v == 0L) q.deleteSetting("delay.$kind.$key") else q.putSetting("delay.$kind.$key", v.toString())
    }

    private fun decode(raw: String?): PlayerSettings =
        raw?.let { runCatching { json.decodeFromString(PlayerSettings.serializer(), it) }.getOrNull() } ?: PlayerSettings()

    companion object {
        private const val KEY = "player_settings"
        private const val KEY_MIGRATED = "player_settings_large_buffer_default"

        /** Stable key for per-content offsets: "<sourceId>:<LIVE|MOVIE|EPISODE>:<id>". */
        fun contentKey(sourceId: Long, type: String, id: String) = "$sourceId:$type:$id"
    }
}

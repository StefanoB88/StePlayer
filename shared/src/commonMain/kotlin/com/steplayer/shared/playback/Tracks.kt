package com.steplayer.shared.playback

import com.steplayer.shared.settings.PlayerSettings
import com.steplayer.shared.settings.SubtitleMode

/** Platform-neutral description of an audio or subtitle track. */
data class TrackInfo(
    val id: String,
    /** Raw language tag as reported by the stream ("ita", "it", "Italian", "und", null). */
    val language: String?,
    val label: String?,
    val channelCount: Int = 0,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
)

object Tracks {
    private val ALIASES: Map<String, String> = buildMap {
        fun add(code: String, vararg names: String) { put(code, code); names.forEach { put(it, code) } }
        add("it", "ita", "italian", "italiano")
        add("en", "eng", "english", "inglese")
        add("es", "spa", "spanish", "espanol", "español", "spagnolo", "castellano")
        add("fr", "fra", "fre", "french", "francais", "français", "francese")
        add("de", "deu", "ger", "german", "deutsch", "tedesco")
        add("pt", "por", "portuguese", "portugues", "português", "portoghese")
        add("ru", "rus", "russian", "russo")
        add("ar", "ara", "arabic", "arabo")
        add("tr", "tur", "turkish", "turco")
        add("pl", "pol", "polish", "polacco")
        add("ro", "ron", "rum", "romanian", "rumeno")
        add("sq", "sqi", "alb", "albanian", "albanese")
        add("nl", "nld", "dut", "dutch", "olandese")
        add("el", "ell", "gre", "greek", "greco")
        add("zh", "zho", "chi", "chinese", "cinese")
        add("ja", "jpn", "japanese", "giapponese")
        add("hi", "hin", "hindi")
        add("uk", "ukr", "ukrainian", "ucraino")
    }

    private val NAMES = mapOf(
        "it" to "Italiano", "en" to "Inglese", "es" to "Spagnolo", "fr" to "Francese", "de" to "Tedesco",
        "pt" to "Portoghese", "ru" to "Russo", "ar" to "Arabo", "tr" to "Turco", "pl" to "Polacco",
        "ro" to "Rumeno", "sq" to "Albanese", "nl" to "Olandese", "el" to "Greco", "zh" to "Cinese",
        "ja" to "Giapponese", "hi" to "Hindi", "uk" to "Ucraino",
    )

    /** Normalises "ITA", "it-IT", "Italian" → "it". Null for unknown/undetermined. */
    fun normalize(tag: String?): String? {
        val t = tag?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "und" && it != "unknown" } ?: return null
        val base = t.substringBefore('-').substringBefore('_')
        return ALIASES[base] ?: ALIASES[t] ?: base.takeIf { it.length == 2 }
    }

    /** "Italiano · 5.1", "Inglese (forzati)", "Traccia 2". */
    fun displayName(track: TrackInfo, index: Int): String {
        val lang = normalize(track.language)?.let { NAMES[it] ?: it.uppercase() }
        val base = lang ?: track.label?.takeIf { it.isNotBlank() } ?: "Traccia ${index + 1}"
        val extra = buildList {
            when (track.channelCount) {
                6 -> add("5.1"); 8 -> add("7.1"); 2 -> add("Stereo"); 1 -> add("Mono")
            }
            if (track.isForced) add("forzati")
        }
        return if (extra.isEmpty()) base else "$base · ${extra.joinToString(" · ")}"
    }

    private fun audioPriority(settings: PlayerSettings, deviceLanguages: List<String>): List<String> =
        (settings.preferredAudioLanguages.ifEmpty { deviceLanguages }).mapNotNull { normalize(it) }.distinct()

    fun pickAudio(tracks: List<TrackInfo>, settings: PlayerSettings, deviceLanguages: List<String>): TrackInfo? {
        if (tracks.isEmpty()) return null
        for (lang in audioPriority(settings, deviceLanguages)) {
            val matches = tracks.filter { normalize(it.language) == lang }
            if (matches.isNotEmpty()) return matches.firstOrNull { it.isDefault } ?: matches.first()
        }
        return tracks.firstOrNull { it.isDefault } ?: tracks.first()
    }

    /**
     * OFF → none. ALWAYS → subtitles in the user's language. AUTO → subtitles in the user's
     * language only when the audio is in another language; forced subtitles (foreign dialogue)
     * in the audio language are always picked when present.
     */
    fun pickSubtitle(
        tracks: List<TrackInfo>,
        selectedAudio: TrackInfo?,
        settings: PlayerSettings,
        deviceLanguages: List<String>,
    ): TrackInfo? {
        if (settings.subtitleMode == SubtitleMode.OFF || tracks.isEmpty()) return null
        val userLang = normalize(settings.preferredSubtitleLanguage) ?: deviceLanguages.firstNotNullOfOrNull { normalize(it) }
        val audioLang = normalize(selectedAudio?.language)
        val full = tracks.filter { !it.isForced && normalize(it.language) == userLang }
        val forced = tracks.firstOrNull { it.isForced && (normalize(it.language) == audioLang || normalize(it.language) == userLang) }
        return when (settings.subtitleMode) {
            SubtitleMode.ALWAYS -> full.firstOrNull() ?: forced
            SubtitleMode.AUTO -> if (audioLang != null && userLang != null && audioLang != userLang) full.firstOrNull() ?: forced else forced
            SubtitleMode.OFF -> null
        }
    }
}

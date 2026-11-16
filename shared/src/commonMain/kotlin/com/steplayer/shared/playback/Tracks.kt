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

/** Kind of track beyond its language: kept as separate entries when de-duplicating. */
enum class TrackRole { MAIN, SDH, COMMENTARY, DESCRIPTION }

object Tracks {
    /** ISO 639-1 code → Italian name, plus every alias (ISO 639-2/B/T, English, Italian, native) mapped to the code. */
    private val NAMES = mutableMapOf<String, String>()
    private val ALIASES: Map<String, String> = buildMap {
        fun add(code: String, name: String, vararg aliases: String) {
            NAMES[code] = name
            put(code, code); put(name.lowercase(), code); aliases.forEach { put(it, code) }
        }
        add("it", "Italiano", "ita", "italian")
        add("en", "Inglese", "eng", "english")
        add("es", "Spagnolo", "spa", "spanish", "espanol", "español", "castellano", "castilian")
        add("fr", "Francese", "fra", "fre", "french", "francais", "français")
        add("de", "Tedesco", "deu", "ger", "german", "deutsch")
        add("pt", "Portoghese", "por", "portuguese", "portugues", "português")
        add("ru", "Russo", "rus", "russian", "русский")
        add("ar", "Arabo", "ara", "arabic", "العربية")
        add("tr", "Turco", "tur", "turkish", "türkçe", "turkce")
        add("pl", "Polacco", "pol", "polish", "polski")
        add("ro", "Rumeno", "ron", "rum", "romanian", "română", "romana")
        add("sq", "Albanese", "sqi", "alb", "albanian", "shqip")
        add("nl", "Olandese", "nld", "dut", "dutch", "nederlands", "flemish")
        add("el", "Greco", "ell", "gre", "greek", "ελληνικά")
        add("zh", "Cinese", "zho", "chi", "chinese", "mandarin", "cantonese", "中文")
        add("ja", "Giapponese", "jpn", "japanese", "日本語")
        add("ko", "Coreano", "kor", "korean", "한국어")
        add("hi", "Hindi", "hin")
        add("uk", "Ucraino", "ukr", "ukrainian", "українська")
        add("sv", "Svedese", "swe", "swedish", "svenska")
        add("no", "Norvegese", "nor", "nob", "nno", "nb", "nn", "norwegian", "norsk")
        add("da", "Danese", "dan", "danish", "dansk")
        add("fi", "Finlandese", "fin", "finnish", "suomi")
        add("cs", "Ceco", "ces", "cze", "czech", "čeština", "cestina")
        add("sk", "Slovacco", "slk", "slo", "slovak", "slovenčina")
        add("sl", "Sloveno", "slv", "slovenian", "slovene", "slovenščina")
        add("hr", "Croato", "hrv", "croatian", "hrvatski")
        add("sr", "Serbo", "srp", "serbian", "srpski", "српски")
        add("bs", "Bosniaco", "bos", "bosnian", "bosanski")
        add("mk", "Macedone", "mkd", "mac", "macedonian")
        add("bg", "Bulgaro", "bul", "bulgarian", "български")
        add("hu", "Ungherese", "hun", "hungarian", "magyar")
        add("he", "Ebraico", "heb", "hebrew", "iw", "עברית")
        add("fa", "Persiano", "fas", "per", "persian", "farsi")
        add("ur", "Urdu", "urd")
        add("bn", "Bengalese", "ben", "bengali", "bangla")
        add("ta", "Tamil", "tam")
        add("te", "Telugu", "tel")
        add("pa", "Punjabi", "pan")
        add("th", "Thailandese", "tha", "thai")
        add("vi", "Vietnamita", "vie", "vietnamese")
        add("id", "Indonesiano", "ind", "indonesian", "bahasa")
        add("ms", "Malese", "msa", "may", "malay")
        add("tl", "Filippino", "tgl", "fil", "filipino", "tagalog")
        add("ca", "Catalano", "cat", "catalan", "català")
        add("eu", "Basco", "eus", "baq", "basque", "euskara")
        add("gl", "Galiziano", "glg", "galician")
        add("et", "Estone", "est", "estonian")
        add("lv", "Lettone", "lav", "latvian")
        add("lt", "Lituano", "lit", "lithuanian")
        add("is", "Islandese", "isl", "ice", "icelandic")
        add("ga", "Irlandese", "gle", "irish")
        add("cy", "Gallese", "cym", "wel", "welsh")
        add("hy", "Armeno", "hye", "arm", "armenian")
        add("ka", "Georgiano", "kat", "geo", "georgian")
        add("az", "Azero", "aze", "azerbaijani")
        add("kk", "Kazako", "kaz", "kazakh")
        add("ku", "Curdo", "kur", "kurdish")
        add("so", "Somalo", "som", "somali")
        add("sw", "Swahili", "swa", "swahili")
        add("am", "Amarico", "amh", "amharic")
        add("la", "Latino", "lat", "latin")
    }

    private val ROLE_WORDS = mapOf(
        TrackRole.SDH to listOf("sdh", "cc", "non udenti", "hearing", "hoh", "audiolesi"),
        TrackRole.COMMENTARY to listOf("commento", "commentary", "director", "regista"),
        TrackRole.DESCRIPTION to listOf("audiodescrizione", "audio description", "descriptive", "descrizione", "ad"),
    )

    /** Normalises "ITA", "it-IT", "Italian" → "it"; unknown 3-letter codes stay as they are ("tgk"). Null for unknown/undetermined. */
    fun normalize(tag: String?): String? {
        val t = tag?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "und" && it != "unknown" && it != "mul" && it != "zxx" } ?: return null
        val base = t.substringBefore('-').substringBefore('_')
        return ALIASES[t] ?: ALIASES[base] ?: base.takeIf { (it.length == 2 || it.length == 3) && it.all(Char::isLetter) }
    }

    /**
     * The track's language: its tag, or — when the stream has none — read from its name ("English",
     * "Track 1 - [Italian]", "ITA AC3 5.1"). Null when unknown.
     */
    fun languageOf(track: TrackInfo): String? {
        normalize(track.language)?.let { return it }
        val label = track.label?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        ALIASES[label.lowercase()]?.let { return it } // the whole name is a language: "English", "ita"
        // Inside a longer name only unambiguous words count: full language names ("Track 1 - [Italian]") or
        // codes written in capitals ("ITA AC3 5.1"). Lower-case short words ("per", "may", "cat") are ordinary words.
        return Regex("\\p{L}+").findAll(label).map { it.value }.firstNotNullOfOrNull { w ->
            val code = ALIASES[w.lowercase()] ?: return@firstNotNullOfOrNull null
            code.takeIf { w.length >= 4 || (w.length in 2..3 && w == w.uppercase()) }
        }
    }

    fun roleOf(track: TrackInfo): TrackRole {
        val label = " " + (track.label?.lowercase() ?: "") + " "
        for ((role, words) in ROLE_WORDS) {
            if (words.any { w -> Regex("[^\\p{L}]" + Regex.escape(w) + "[^\\p{L}]").containsMatchIn(label) }) return role
        }
        return TrackRole.MAIN
    }

    /** "Italiano · 5.1", "Inglese (forzati)", "Inglese · non udenti", "Traccia 2". */
    fun displayName(track: TrackInfo, index: Int): String {
        val code = languageOf(track)
        val lang = code?.let { NAMES[it] ?: it.uppercase() }
        val base = lang ?: track.label?.takeIf { it.isNotBlank() }?.trim() ?: "Traccia ${index + 1}"
        val extra = buildList {
            when (track.channelCount) {
                6 -> add("5.1"); 8 -> add("7.1"); 2 -> add("Stereo"); 1 -> add("Mono")
            }
            when (roleOf(track)) {
                TrackRole.SDH -> add("non udenti"); TrackRole.COMMENTARY -> add("commento"); TrackRole.DESCRIPTION -> add("audiodescrizione")
                TrackRole.MAIN -> Unit
            }
            if (track.isForced) add("forzati")
        }
        return if (extra.isEmpty()) base else "$base · ${extra.joinToString(" · ")}"
    }

    /**
     * One entry per language (and per forced / non udenti / commento variant), keeping every language the stream has.
     * Among duplicates of the same language the one in use wins, then the stream's default, then the one with
     * more channels. Tracks with no recognisable language are all kept (they may be different languages),
     * except exact duplicates of the same name. Order of first appearance is preserved.
     */
    fun distinct(tracks: List<TrackInfo>, selectedId: String? = null): List<TrackInfo> {
        val groups = LinkedHashMap<String, MutableList<TrackInfo>>()
        tracks.forEachIndexed { i, t ->
            val lang = languageOf(t)
            val key = when {
                lang != null -> "lang:$lang:${t.isForced}:${roleOf(t)}"
                !t.label.isNullOrBlank() -> "label:${t.label.trim().lowercase()}:${t.isForced}"
                else -> "index:$i"
            }
            groups.getOrPut(key) { mutableListOf() } += t
        }
        return groups.values.map { group ->
            group.firstOrNull { it.id == selectedId }
                ?: group.maxWithOrNull(compareBy<TrackInfo>({ it.isDefault }, { it.channelCount }).thenByDescending { group.indexOf(it) })!!
        }
    }

    private fun audioPriority(settings: PlayerSettings, deviceLanguages: List<String>): List<String> =
        (settings.preferredAudioLanguages.ifEmpty { deviceLanguages }).mapNotNull { normalize(it) }.distinct()

    fun pickAudio(tracks: List<TrackInfo>, settings: PlayerSettings, deviceLanguages: List<String>): TrackInfo? {
        if (tracks.isEmpty()) return null
        val main = tracks.filter { roleOf(it) == TrackRole.MAIN }.ifEmpty { tracks }
        for (lang in audioPriority(settings, deviceLanguages)) {
            val matches = main.filter { languageOf(it) == lang }
            if (matches.isNotEmpty()) return matches.firstOrNull { it.isDefault } ?: matches.first()
        }
        return main.firstOrNull { it.isDefault } ?: main.first()
    }

    /**
     * OFF → none. ALWAYS → subtitles in the user's language; when the stream has none, its default subtitles,
     * then the first full ones (a "Sempre" choice must show something). AUTO → subtitles in the user's language
     * only when the audio is in another language; forced subtitles (foreign dialogue) in the audio language are
     * always picked when present.
     */
    fun pickSubtitle(
        tracks: List<TrackInfo>,
        selectedAudio: TrackInfo?,
        settings: PlayerSettings,
        deviceLanguages: List<String>,
    ): TrackInfo? {
        if (settings.subtitleMode == SubtitleMode.OFF || tracks.isEmpty()) return null
        val userLang = normalize(settings.preferredSubtitleLanguage) ?: deviceLanguages.firstNotNullOfOrNull { normalize(it) }
        val audioLang = selectedAudio?.let { languageOf(it) }
        val fullTracks = tracks.filter { !it.isForced }
        // Plain subtitles before "non udenti" ones in the same language.
        val full = fullTracks.filter { languageOf(it) == userLang }.sortedBy { if (roleOf(it) == TrackRole.MAIN) 0 else 1 }
        val forced = tracks.firstOrNull { it.isForced && (languageOf(it) == audioLang || languageOf(it) == userLang) }
        return when (settings.subtitleMode) {
            SubtitleMode.ALWAYS -> full.firstOrNull() ?: forced ?: fullTracks.firstOrNull { it.isDefault } ?: fullTracks.firstOrNull()
            SubtitleMode.AUTO -> if (audioLang != null && userLang != null && audioLang != userLang) full.firstOrNull() ?: forced else forced
            SubtitleMode.OFF -> null
        }
    }
}

// Port of playback/Tracks.kt.
import type { PlayerSettings, TrackInfo } from '../api.ts';

export type TrackRole = 'MAIN' | 'SDH' | 'COMMENTARY' | 'DESCRIPTION';

/** ISO 639-1 code → Italian name; every alias (ISO 639-2/B/T, English, Italian, native) → code. */
const NAMES = new Map<string, string>();
const ALIASES = new Map<string, string>();
function add(code: string, name: string, ...aliases: string[]): void {
  NAMES.set(code, name);
  ALIASES.set(code, code);
  ALIASES.set(name.toLowerCase(), code);
  for (const a of aliases) ALIASES.set(a, code);
}
add('it', 'Italiano', 'ita', 'italian');
add('en', 'Inglese', 'eng', 'english');
add('es', 'Spagnolo', 'spa', 'spanish', 'espanol', 'español', 'castellano', 'castilian');
add('fr', 'Francese', 'fra', 'fre', 'french', 'francais', 'français');
add('de', 'Tedesco', 'deu', 'ger', 'german', 'deutsch');
add('pt', 'Portoghese', 'por', 'portuguese', 'portugues', 'português');
add('ru', 'Russo', 'rus', 'russian', 'русский');
add('ar', 'Arabo', 'ara', 'arabic', 'العربية');
add('tr', 'Turco', 'tur', 'turkish', 'türkçe', 'turkce');
add('pl', 'Polacco', 'pol', 'polish', 'polski');
add('ro', 'Rumeno', 'ron', 'rum', 'romanian', 'română', 'romana');
add('sq', 'Albanese', 'sqi', 'alb', 'albanian', 'shqip');
add('nl', 'Olandese', 'nld', 'dut', 'dutch', 'nederlands', 'flemish');
add('el', 'Greco', 'ell', 'gre', 'greek', 'ελληνικά');
add('zh', 'Cinese', 'zho', 'chi', 'chinese', 'mandarin', 'cantonese', '中文');
add('ja', 'Giapponese', 'jpn', 'japanese', '日本語');
add('ko', 'Coreano', 'kor', 'korean', '한국어');
add('hi', 'Hindi', 'hin');
add('uk', 'Ucraino', 'ukr', 'ukrainian', 'українська');
add('sv', 'Svedese', 'swe', 'swedish', 'svenska');
add('no', 'Norvegese', 'nor', 'nob', 'nno', 'nb', 'nn', 'norwegian', 'norsk');
add('da', 'Danese', 'dan', 'danish', 'dansk');
add('fi', 'Finlandese', 'fin', 'finnish', 'suomi');
add('cs', 'Ceco', 'ces', 'cze', 'czech', 'čeština', 'cestina');
add('sk', 'Slovacco', 'slk', 'slo', 'slovak', 'slovenčina');
add('sl', 'Sloveno', 'slv', 'slovenian', 'slovene', 'slovenščina');
add('hr', 'Croato', 'hrv', 'croatian', 'hrvatski');
add('sr', 'Serbo', 'srp', 'serbian', 'srpski', 'српски');
add('bs', 'Bosniaco', 'bos', 'bosnian', 'bosanski');
add('mk', 'Macedone', 'mkd', 'mac', 'macedonian');
add('bg', 'Bulgaro', 'bul', 'bulgarian', 'български');
add('hu', 'Ungherese', 'hun', 'hungarian', 'magyar');
add('he', 'Ebraico', 'heb', 'hebrew', 'iw', 'עברית');
add('fa', 'Persiano', 'fas', 'per', 'persian', 'farsi');
add('ur', 'Urdu', 'urd');
add('bn', 'Bengalese', 'ben', 'bengali', 'bangla');
add('ta', 'Tamil', 'tam');
add('te', 'Telugu', 'tel');
add('pa', 'Punjabi', 'pan');
add('th', 'Thailandese', 'tha', 'thai');
add('vi', 'Vietnamita', 'vie', 'vietnamese');
add('id', 'Indonesiano', 'ind', 'indonesian', 'bahasa');
add('ms', 'Malese', 'msa', 'may', 'malay');
add('tl', 'Filippino', 'tgl', 'fil', 'filipino', 'tagalog');
add('ca', 'Catalano', 'cat', 'catalan', 'català');
add('eu', 'Basco', 'eus', 'baq', 'basque', 'euskara');
add('gl', 'Galiziano', 'glg', 'galician');
add('et', 'Estone', 'est', 'estonian');
add('lv', 'Lettone', 'lav', 'latvian');
add('lt', 'Lituano', 'lit', 'lithuanian');
add('is', 'Islandese', 'isl', 'ice', 'icelandic');
add('ga', 'Irlandese', 'gle', 'irish');
add('cy', 'Gallese', 'cym', 'wel', 'welsh');
add('hy', 'Armeno', 'hye', 'arm', 'armenian');
add('ka', 'Georgiano', 'kat', 'geo', 'georgian');
add('az', 'Azero', 'aze', 'azerbaijani');
add('kk', 'Kazako', 'kaz', 'kazakh');
add('ku', 'Curdo', 'kur', 'kurdish');
add('so', 'Somalo', 'som', 'somali');
add('sw', 'Swahili', 'swa', 'swahili');
add('am', 'Amarico', 'amh', 'amharic');
add('la', 'Latino', 'lat', 'latin');

const ROLE_WORDS: [TrackRole, string[]][] = [
  ['SDH', ['sdh', 'cc', 'non udenti', 'hearing', 'hoh', 'audiolesi']],
  ['COMMENTARY', ['commento', 'commentary', 'director', 'regista']],
  ['DESCRIPTION', ['audiodescrizione', 'audio description', 'descriptive', 'descrizione', 'ad']],
];
// Letters, Unicode-aware without the `u` flag's \p{L} (Chromium 56 lacks property escapes).
const LETTERS = /[A-Za-zÀ-ɏͰ-ϿЀ-ӿ֐-ۿ぀-ヿ一-鿿가-힯]+/g;
const NOT_LETTER = '[^A-Za-z\\u00C0-\\u024F\\u0370-\\u03FF\\u0400-\\u04FF]';

function escapeRe(s: string): string { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }

export const Tracks = {
  /** Normalises "ITA", "it-IT", "Italian" → "it"; unknown 3-letter codes stay ("tgk"). Null for unknown/undetermined. */
  normalize(tag: string | null | undefined): string | null {
    if (tag == null) return null;
    const t = tag.trim().toLowerCase();
    if (t === '' || t === 'und' || t === 'unknown' || t === 'mul' || t === 'zxx') return null;
    const base = t.split('-')[0].split('_')[0];
    return ALIASES.get(t) ?? ALIASES.get(base) ?? ((base.length === 2 || base.length === 3) && /^[a-z]+$/.test(base) ? base : null);
  },

  /** The track's language: its tag, or — when the stream has none — read from its name ("Track 1 - [Italian]", "ITA AC3"). */
  languageOf(track: TrackInfo): string | null {
    const fromTag = Tracks.normalize(track.language);
    if (fromTag != null) return fromTag;
    const label = track.label != null ? track.label.trim() : '';
    if (label === '') return null;
    const whole = ALIASES.get(label.toLowerCase());
    if (whole != null) return whole;
    // Inside a longer name only full language names or codes in capitals count ("per", "cat" are ordinary words).
    const words = label.match(LETTERS) || [];
    for (const w of words) {
      const code = ALIASES.get(w.toLowerCase());
      if (code != null && (w.length >= 4 || (w.length >= 2 && w.length <= 3 && w === w.toUpperCase()))) return code;
    }
    return null;
  },

  roleOf(track: TrackInfo): TrackRole {
    const label = ` ${(track.label || '').toLowerCase()} `;
    for (const [role, words] of ROLE_WORDS) {
      if (words.some((w) => new RegExp(NOT_LETTER + escapeRe(w) + NOT_LETTER).test(label))) return role;
    }
    return 'MAIN';
  },

  /** "Italiano · 5.1", "Inglese · non udenti", "Inglese · forzati", "Traccia 2". */
  displayName(track: TrackInfo, index: number): string {
    const code = Tracks.languageOf(track);
    const lang = code != null ? (NAMES.get(code) ?? code.toUpperCase()) : null;
    const base = lang ?? (track.label != null && track.label.trim() !== '' ? track.label.trim() : `Traccia ${index + 1}`);
    const extra: string[] = [];
    if (track.channelCount === 6) extra.push('5.1');
    else if (track.channelCount === 8) extra.push('7.1');
    else if (track.channelCount === 2) extra.push('Stereo');
    else if (track.channelCount === 1) extra.push('Mono');
    const role = Tracks.roleOf(track);
    if (role === 'SDH') extra.push('non udenti');
    else if (role === 'COMMENTARY') extra.push('commento');
    else if (role === 'DESCRIPTION') extra.push('audiodescrizione');
    if (track.isForced) extra.push('forzati');
    return extra.length === 0 ? base : `${base} · ${extra.join(' · ')}`;
  },

  /**
   * One entry per language (and per forced / non udenti / commento variant), keeping every language. Among duplicates
   * the track in use wins, then the default, then more channels. Unknown-language tracks all stay (except identical names).
   */
  distinct(tracks: TrackInfo[], selectedId: string | null = null): TrackInfo[] {
    const order: string[] = [];
    const groups = new Map<string, TrackInfo[]>();
    tracks.forEach((t, i) => {
      const lang = Tracks.languageOf(t);
      const key = lang != null ? `lang:${lang}:${t.isForced}:${Tracks.roleOf(t)}`
        : t.label != null && t.label.trim() !== '' ? `label:${t.label.trim().toLowerCase()}:${t.isForced}` : `index:${i}`;
      if (!groups.has(key)) { groups.set(key, []); order.push(key); }
      groups.get(key)!.push(t);
    });
    return order.map((key) => {
      const g = groups.get(key)!;
      const sel = g.filter((t) => t.id === selectedId)[0];
      if (sel) return sel;
      let best = g[0];
      for (const t of g) {
        if (Number(t.isDefault) > Number(best.isDefault) || (t.isDefault === best.isDefault && t.channelCount > best.channelCount)) best = t;
      }
      return best;
    });
  },

  audioPriority(settings: PlayerSettings, deviceLanguages: string[]): string[] {
    const src = settings.preferredAudioLanguages.length > 0 ? settings.preferredAudioLanguages : deviceLanguages;
    const out: string[] = [];
    for (const l of src) {
      const n = Tracks.normalize(l);
      if (n != null && out.indexOf(n) < 0) out.push(n);
    }
    return out;
  },

  pickAudio(tracks: TrackInfo[], settings: PlayerSettings, deviceLanguages: string[]): TrackInfo | null {
    if (tracks.length === 0) return null;
    const mainOnly = tracks.filter((t) => Tracks.roleOf(t) === 'MAIN');
    const main = mainOnly.length > 0 ? mainOnly : tracks;
    for (const lang of Tracks.audioPriority(settings, deviceLanguages)) {
      const matches = main.filter((t) => Tracks.languageOf(t) === lang);
      if (matches.length > 0) return matches.filter((t) => t.isDefault)[0] ?? matches[0];
    }
    return main.filter((t) => t.isDefault)[0] ?? main[0];
  },

  /**
   * OFF → none. ALWAYS → subtitles in the user's language; otherwise the stream's default, then the first full ones.
   * AUTO → user's language only when the audio is in another language; forced subtitles always when present.
   */
  pickSubtitle(tracks: TrackInfo[], settings: PlayerSettings, deviceLanguages: string[], audioLanguage: string | null): TrackInfo | null {
    if (settings.subtitleMode === 'OFF' || tracks.length === 0) return null;
    let userLang = Tracks.normalize(settings.preferredSubtitleLanguage);
    if (userLang == null) {
      for (const l of deviceLanguages) {
        const n = Tracks.normalize(l);
        if (n != null) { userLang = n; break; }
      }
    }
    const audioLang = Tracks.normalize(audioLanguage);
    const fullTracks = tracks.filter((t) => !t.isForced);
    const full = fullTracks.filter((t) => Tracks.languageOf(t) === userLang)
      .sort((a, b) => (Tracks.roleOf(a) === 'MAIN' ? 0 : 1) - (Tracks.roleOf(b) === 'MAIN' ? 0 : 1));
    const forced = tracks.filter((t) => t.isForced && (Tracks.languageOf(t) === audioLang || Tracks.languageOf(t) === userLang))[0] ?? null;
    if (settings.subtitleMode === 'ALWAYS') return full[0] ?? forced ?? fullTracks.filter((t) => t.isDefault)[0] ?? fullTracks[0] ?? null;
    // AUTO
    return audioLang != null && userLang != null && audioLang !== userLang ? (full[0] ?? forced) : forced;
  },
};

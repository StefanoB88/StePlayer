// Port of playback/Tracks.kt.
import type { PlayerSettings, TrackInfo } from '../api.ts';

const ALIASES = new Map<string, string>();
function add(code: string, ...names: string[]): void {
  ALIASES.set(code, code);
  for (const n of names) ALIASES.set(n, code);
}
add('it', 'ita', 'italian', 'italiano');
add('en', 'eng', 'english', 'inglese');
add('es', 'spa', 'spanish', 'espanol', 'español', 'spagnolo', 'castellano');
add('fr', 'fra', 'fre', 'french', 'francais', 'français', 'francese');
add('de', 'deu', 'ger', 'german', 'deutsch', 'tedesco');
add('pt', 'por', 'portuguese', 'portugues', 'português', 'portoghese');
add('ru', 'rus', 'russian', 'russo');
add('ar', 'ara', 'arabic', 'arabo');
add('tr', 'tur', 'turkish', 'turco');
add('pl', 'pol', 'polish', 'polacco');
add('ro', 'ron', 'rum', 'romanian', 'rumeno');
add('sq', 'sqi', 'alb', 'albanian', 'albanese');
add('nl', 'nld', 'dut', 'dutch', 'olandese');
add('el', 'ell', 'gre', 'greek', 'greco');
add('zh', 'zho', 'chi', 'chinese', 'cinese');
add('ja', 'jpn', 'japanese', 'giapponese');
add('hi', 'hin', 'hindi');
add('uk', 'ukr', 'ukrainian', 'ucraino');

const NAMES = new Map<string, string>([
  ['it', 'Italiano'], ['en', 'Inglese'], ['es', 'Spagnolo'], ['fr', 'Francese'], ['de', 'Tedesco'],
  ['pt', 'Portoghese'], ['ru', 'Russo'], ['ar', 'Arabo'], ['tr', 'Turco'], ['pl', 'Polacco'],
  ['ro', 'Rumeno'], ['sq', 'Albanese'], ['nl', 'Olandese'], ['el', 'Greco'], ['zh', 'Cinese'],
  ['ja', 'Giapponese'], ['hi', 'Hindi'], ['uk', 'Ucraino'],
]);

export const Tracks = {
  /** Normalises "ITA", "it-IT", "Italian" → "it". Null for unknown/undetermined. */
  normalize(tag: string | null | undefined): string | null {
    if (tag == null) return null;
    const t = tag.trim().toLowerCase();
    if (t === '' || t === 'und' || t === 'unknown') return null;
    let base = t;
    const dash = base.indexOf('-');
    if (dash >= 0) base = base.substring(0, dash);
    const us = base.indexOf('_');
    if (us >= 0) base = base.substring(0, us);
    return ALIASES.get(base) ?? ALIASES.get(t) ?? (base.length === 2 ? base : null);
  },

  /** "Italiano · 5.1", "Inglese · forzati", "Traccia 2". */
  displayName(track: TrackInfo, index: number): string {
    const code = Tracks.normalize(track.language);
    const lang = code != null ? (NAMES.get(code) ?? code.toUpperCase()) : null;
    const base = lang ?? (track.label != null && track.label.trim() !== '' ? track.label : `Traccia ${index + 1}`);
    const extra: string[] = [];
    if (track.channelCount === 6) extra.push('5.1');
    else if (track.channelCount === 8) extra.push('7.1');
    else if (track.channelCount === 2) extra.push('Stereo');
    else if (track.channelCount === 1) extra.push('Mono');
    if (track.isForced) extra.push('forzati');
    return extra.length === 0 ? base : `${base} · ${extra.join(' · ')}`;
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
    for (const lang of Tracks.audioPriority(settings, deviceLanguages)) {
      const matches = tracks.filter((t) => Tracks.normalize(t.language) === lang);
      if (matches.length > 0) return matches.filter((t) => t.isDefault)[0] ?? matches[0];
    }
    return tracks.filter((t) => t.isDefault)[0] ?? tracks[0];
  },

  /**
   * OFF → none. ALWAYS → subtitles in the user's language. AUTO → subtitles in the user's
   * language only when the audio is in another language; forced subtitles (foreign dialogue) in
   * the audio language are always picked when present.
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
    const full = tracks.filter((t) => !t.isForced && Tracks.normalize(t.language) === userLang);
    const forced = tracks.filter((t) => t.isForced && (Tracks.normalize(t.language) === audioLang || Tracks.normalize(t.language) === userLang))[0] ?? null;
    if (settings.subtitleMode === 'ALWAYS') return full[0] ?? forced;
    // AUTO
    return audioLang != null && userLang != null && audioLang !== userLang ? (full[0] ?? forced) : forced;
  },
};

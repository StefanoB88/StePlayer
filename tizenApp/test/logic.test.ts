// Ports of PlaybackTest.kt, PlayerFeaturesTest.kt, QueryParserTest.kt, ZapperTest.kt, ExpiryRemindersTest.kt.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import type { Channel, PlayerSettings, Source, TrackInfo } from '../src/core/api.ts';
import { BufferAdvisor, PlaybackErrors, RetryPolicy, StallWatchdog } from '../src/core/playback/playbackErrors.ts';
import { Tracks } from '../src/core/playback/tracks.ts';
import { noticesFor } from '../src/core/reminders/expiryReminders.ts';
import { QueryParser, editDistance } from '../src/core/search/queryParser.ts';
import { decodeSettings, defaultPlayerSettings } from '../src/core/settings/playerPreferences.ts';
import { ChannelZapper } from '../src/core/zap/channelZapper.ts';
import { withScheme, upgradeIfXtream, withSchemes } from '../src/core/data/sourceRepository.ts';
import { sizedArtwork } from '../src/core/util/artworkUrl.ts';
import { XtreamAuthErrors, XtreamException } from '../src/core/xtream/client.ts';
import { NetworkErrors } from '../src/core/net/networkErrors.ts';
import { HttpError } from '../src/core/net/http.ts';
import { utcMs } from './helpers.ts';

describe('retry and stall', () => {
  test('max connections waits longer and gives up', () => {
    const p = new RetryPolicy(true);
    const e = PlaybackErrors.fromHttpStatus(458, 1);
    assert.equal(e.kind, 'MAX_CONNECTIONS');
    assert.ok(e.message.includes('1 connessione'));
    assert.deepEqual([0, 1, 2, 3].map(() => p.nextDelayMs(e)), [4_000, 10_000, 20_000, null]);
    p.reset();
    assert.equal(p.nextDelayMs(e), 4_000);
  });

  test('non retryable stops immediately', () => {
    assert.equal(new RetryPolicy(true).nextDelayMs(PlaybackErrors.fromHttpStatus(404)), null);
    assert.equal(new RetryPolicy(false).nextDelayMs(PlaybackErrors.unsupported('avi')), null);
    const vod = new RetryPolicy(false);
    assert.deepEqual([vod.nextDelayMs(PlaybackErrors.timeout()), vod.nextDelayMs(PlaybackErrors.timeout()), vod.nextDelayMs(PlaybackErrors.timeout())], [1_500, 4_000, null]);
  });

  test('error texts', () => {
    assert.equal(PlaybackErrors.fromHttpStatus(401).title, 'Accesso negato');
    assert.equal(PlaybackErrors.fromHttpStatus(503).message, 'Il server del provider non risponde correttamente (503).');
    assert.equal(PlaybackErrors.fromHttpStatus(302).message, 'Si è verificato un errore (HTTP 302). Riprova tra poco.');
    assert.ok(PlaybackErrors.maxConnections(null).message.includes('hai raggiunto il limite di connessioni'));
    assert.ok(PlaybackErrors.maxConnections(3).message.includes('3 connessioni'));
    assert.equal(PlaybackErrors.unsupported(null).message, 'Questo dispositivo non riesce a riprodurre il formato.');
  });

  test('watchdog detects frozen live', () => {
    const w = new StallWatchdog(true);
    assert.equal(w.tick(0, 1_000, true), false);
    assert.equal(w.tick(5_000, 6_000, true), false); // advancing
    assert.equal(w.tick(10_000, 6_000, true), false); // stuck 5s
    assert.equal(w.tick(10_000, 6_000, false), false); // user paused → never stalled
    assert.equal(w.tick(20_000, 6_000, true), false); // resumed: timer restarted at 10s
    assert.equal(w.tick(22_000, 6_000, true), true); // stuck 12s
  });
});

describe('buffer advisor', () => {
  test('auto escalates after two stalls in ten minutes', () => {
    const b = new BufferAdvisor('AUTO');
    assert.equal(b.profile().name, 'Normale');
    assert.equal(b.onStall(0), false);
    assert.equal(b.onStall(11 * 60_000), false); // first stall expired
    assert.equal(b.onStall(12 * 60_000), true);
    assert.equal(b.profile().name, 'Grande');
    assert.equal(b.onStall(13 * 60_000), false); // already escalated
    assert.equal(new BufferAdvisor('LARGE').profile().name, 'Grande');
    assert.equal(new BufferAdvisor('NORMAL').onStall(0), false);
  });

  test('large keeps big reserve but starts fast', () => {
    const large = new BufferAdvisor('LARGE').profile();
    assert.equal(large.maxBufferMs, 120_000);
    assert.equal(large.minBufferMs, 30_000);
    assert.equal(large.bufferForPlaybackMs, 2_000);
    assert.equal(large.bufferForPlaybackAfterRebufferMs, 5_000);
    assert.ok(large.bufferForPlaybackAfterRebufferMs <= large.minBufferMs);
    assert.deepEqual(new BufferAdvisor('NORMAL').profile(), {
      name: 'Normale', minBufferMs: 15_000, maxBufferMs: 50_000, bufferForPlaybackMs: 1_500,
      bufferForPlaybackAfterRebufferMs: 3_000, liveTargetOffsetMs: 8_000,
    });
  });
});

describe('tracks', () => {
  const t = (id: string, language: string | null, label: string | null, channelCount = 0, isDefault = false, isForced = false): TrackInfo =>
    ({ id, language, label, channelCount, isDefault, isForced });
  const ita = t('a1', 'ita', null, 6);
  const eng = t('a2', 'en', 'English', 2, true);
  const subIt = t('s1', 'it-IT', null);
  const subItForced = t('s2', 'Italian', null, 0, false, true);
  const subEn = t('s3', 'eng', null);
  const settings = (patch: Partial<PlayerSettings> = {}): PlayerSettings => ({ ...defaultPlayerSettings(), ...patch });

  test('normalization and names', () => {
    assert.equal(Tracks.normalize('ITA'), 'it'); assert.equal(Tracks.normalize('Italiano'), 'it');
    assert.equal(Tracks.normalize('ger'), 'de'); assert.equal(Tracks.normalize('und'), null);
    assert.equal(Tracks.normalize('pt_BR'), 'pt'); assert.equal(Tracks.normalize('xx'), 'xx'); assert.equal(Tracks.normalize('xyz'), 'xyz'); assert.equal(Tracks.normalize('x1z'), null); // unknown 3-letter codes kept for grouping
    assert.equal(Tracks.displayName(ita, 0), 'Italiano · 5.1');
    assert.equal(Tracks.displayName(t('x', null, null), 2), 'Traccia 3');
    assert.equal(Tracks.displayName(t('x', 'Italian', null, 0, false, true), 0), 'Italiano · forzati');
  });

  test('audio follows preferences then device', () => {
    assert.equal(Tracks.pickAudio([eng, ita], settings(), ['it-IT'])?.id, 'a1');
    assert.equal(Tracks.pickAudio([eng, ita], settings({ preferredAudioLanguages: ['en'] }), ['it'])?.id, 'a2');
    assert.equal(Tracks.pickAudio([ita, eng], settings(), ['fr'])?.id, 'a2'); // default flag
    assert.equal(Tracks.pickAudio([], settings(), ['it']), null);
  });

  test('subtitle modes', () => {
    const subs = [subEn, subItForced, subIt];
    const auto = settings({ subtitleMode: 'AUTO' });
    assert.equal(Tracks.pickSubtitle(subs, auto, ['it'], eng.language)?.id, 's1'); // English audio → Italian subs
    assert.equal(Tracks.pickSubtitle(subs, auto, ['it'], ita.language)?.id, 's2'); // Italian audio → forced only
    assert.equal(Tracks.pickSubtitle([subEn, subIt], auto, ['it'], ita.language), null);
    assert.equal(Tracks.pickSubtitle(subs, settings({ subtitleMode: 'ALWAYS' }), ['it'], ita.language)?.id, 's1');
    assert.equal(Tracks.pickSubtitle(subs, settings({ subtitleMode: 'OFF' }), ['it'], eng.language), null);
  });
});

describe('player settings', () => {
  test('defaults and lenient decode', () => {
    const d = defaultPlayerSettings();
    assert.equal(d.bufferMode, 'LARGE');
    assert.equal(d.stillWatchingAfterHours, 4);
    assert.equal(d.subtitleMode, 'AUTO');
    assert.deepEqual(d.subtitleStyle, { scale: 1, color: 'WHITE', background: 'NONE', edge: 'OUTLINE', bottomMarginPercent: 8 });
    assert.deepEqual(decodeSettings('garbage'), d);
    const s = decodeSettings('{"bufferMode":"NORMAL","subtitleMode":"BOH","extra":1,"subtitleStyle":{"color":"YELLOW"}}');
    assert.equal(s.bufferMode, 'NORMAL');
    assert.equal(s.subtitleMode, 'AUTO');
    assert.equal(s.subtitleStyle.color, 'YELLOW');
    assert.equal(s.subtitleStyle.edge, 'OUTLINE');
  });
});

describe('query parser', () => {
  test('tokens and stems', () => {
    assert.deepEqual(QueryParser.meaningfulTokens('Il tesoro di Napoli'), ['tesoro', 'napoli']);
    assert.deepEqual(QueryParser.meaningfulTokens('di'), ['di']); // only stop-words → keep them
    assert.equal(QueryParser.ftsExpression(['napoli', 'mare']), 'napol* mare*');
    assert.deepEqual(QueryParser.meaningfulTokens('Città-nascosta!'), ['citta', 'nascosta']);
    assert.deepEqual(QueryParser.meaningfulTokens('constructor toString'), ['constructor', 'tostring']);
    assert.equal(QueryParser.stem('città'), 'città');
  });

  test('distance', () => {
    assert.equal(editDistance('nappoli', 'napoli', 2), 1);
    assert.equal(editDistance('naploi', 'napoli', 2), 1); // transposition
    assert.equal(editDistance('roma', 'napoli', 2), 3);
  });
});

describe('zapper', () => {
  const ch = (id: string, num: number | null): Channel => ({ sourceId: 1, id, num, name: `Canale ${id}`, logo: null, categoryId: null, epgId: null, archiveDays: 0 });
  const list = [ch('a', 1), ch('b', 2), ch('c', 201)];

  test('next/previous wrap and last channel', () => {
    const z = new ChannelZapper(list, list[0]);
    assert.equal(z.next().id, 'b');
    assert.equal(z.next().id, 'c');
    assert.equal(z.next().id, 'a'); // wraps
    assert.equal(z.previous().id, 'c');
    assert.equal(z.back()?.id, 'a'); // "ultimo canale"
    assert.equal(z.back()?.id, 'c'); // toggles
    assert.equal(z.lastChannel?.id, 'a');
  });

  test('number entry', () => {
    const z = new ChannelZapper(list, list[0], 1000);
    assert.equal(z.typeDigit(0, 0), false); // leading zero ignored
    z.typeDigit(2, 100); z.typeDigit(0, 300); z.typeDigit(1, 600);
    assert.equal(z.pendingNumber, '201');
    assert.equal(z.commitIfDue(1000), null); // still typing
    assert.equal(z.commitIfDue(1700), 201);
    assert.equal(z.channelForNumber(201)?.id, 'c');
    assert.equal(z.pendingNumber, '');
    // a pause longer than the timeout starts a new number
    z.typeDigit(5, 5000); z.typeDigit(7, 7000);
    assert.equal(z.pendingNumber, '7');
    const two = new ChannelZapper(list, list[0], 1800, 2);
    two.typeDigit(1, 0);
    assert.equal(two.typeDigit(2, 10), true);
    assert.throws(() => z.typeDigit(10, 0));
  });

  test('number by position only when the list has no numbers', () => {
    const noNums = [ch('x', null), ch('y', null)];
    assert.equal(new ChannelZapper(noNums, noNums[0]).channelForNumber(2)?.id, 'y');
    assert.equal(new ChannelZapper(list, list[0]).channelForNumber(3), null);
  });
});

describe('expiry notice', () => {
  const day = 86_400_000;
  const src = (exp: number | null): Source => ({
    id: 7, name: 'Lista di prova', type: 'XTREAM', url: 'http://srv.example', username: 'u', epgUrl: null, userAgent: null,
    serverTimeOffsetSec: 0, allowedFormats: [], expiresAt: exp, maxConnections: 1, lastSyncAt: null, lastEpgSyncAt: null,
  });

  test('home notices', () => {
    const now = utcMs(2026, 12, 1, 12);
    assert.deepEqual(noticesFor([src(now + 10 * day)], now), []);
    assert.equal(noticesFor([src(now + 2 * day - 1000)], now)[0].daysLeft, 2);
    assert.equal(noticesFor([src(now + 2 * day - 1000)], now)[0].message, "L'abbonamento «Lista di prova» scade tra 2 giorni.");
    assert.equal(noticesFor([src(now + 3_600_000)], now)[0].message, "L'abbonamento «Lista di prova» scade entro 24 ore.");
    const expired = noticesFor([src(now - 1)], now)[0];
    assert.equal(expired.expired, true);
    assert.equal(expired.message, "L'abbonamento «Lista di prova» è scaduto.");
    assert.deepEqual(noticesFor([src(null)], now), []);
  });
});

describe('scheme normalisation', () => {
  test('missing scheme means http only for host-like input', () => {
    assert.equal(withScheme('  server.esempio:8080/lista.m3u '), 'http://server.esempio:8080/lista.m3u');
    assert.equal(withScheme('10.0.0.2:8080/x'), 'http://10.0.0.2:8080/x');
    assert.equal(withScheme('https://server.esempio/x'), 'https://server.esempio/x');
    assert.equal(withScheme('lista'), 'lista');
    assert.equal(withScheme('non valido/x'), 'non valido/x');
    assert.equal(withScheme(''), '');
    const m3u = withSchemes({ kind: 'm3u', name: 'L', listUrl: 'server.esempio/l.m3u', epgUrl: 'guida.esempio/g.xml' });
    assert.ok(m3u.kind === 'm3u' && m3u.listUrl === 'http://server.esempio/l.m3u' && m3u.epgUrl === 'http://guida.esempio/g.xml');
  });

  test('get.php links are upgraded to API Xtream', () => {
    const up = upgradeIfXtream({ kind: 'm3u', name: 'L', listUrl: 'http://srv.example:8080/get.php?username=u&password=p&type=m3u_plus' });
    assert.deepEqual(up, { kind: 'xtream', name: 'L', serverUrl: 'http://srv.example:8080', username: 'u', password: 'p', userAgent: null });
    const same = upgradeIfXtream({ kind: 'm3u', name: 'L', listUrl: 'http://srv.example/lista.m3u' });
    assert.equal(same.kind, 'm3u');
  });
});

describe('artwork url', () => {
  const original = 'https://image.tmdb.org/t/p/original/abc123.jpg';
  test('smallest variant covering the slot', () => {
    assert.equal(sizedArtwork(original, 300), 'https://image.tmdb.org/t/p/w300/abc123.jpg');
    assert.equal(sizedArtwork(original, 301), 'https://image.tmdb.org/t/p/w342/abc123.jpg');
    assert.equal(sizedArtwork(original, 343), 'https://image.tmdb.org/t/p/w500/abc123.jpg');
    assert.equal(sizedArtwork(original, 1100), 'https://image.tmdb.org/t/p/w1280/abc123.jpg');
    assert.equal(sizedArtwork('http://image.tmdb.org/t/p/original/x.png', 160), 'http://image.tmdb.org/t/p/w185/x.png');
  });
  test('never upscales, keeps original for huge slots', () => {
    const w185 = 'https://image.tmdb.org/t/p/w185/abc123.jpg';
    assert.equal(sizedArtwork(w185, 600), w185);
    assert.equal(sizedArtwork(w185, 80), 'https://image.tmdb.org/t/p/w92/abc123.jpg');
    assert.equal(sizedArtwork(original, 1920), original);
  });
  test('other urls and unknown size untouched', () => {
    assert.equal(sizedArtwork(original, 0), original);
    const other = 'http://server.esempio:8080/images/poster/1.jpg';
    assert.equal(sizedArtwork(other, 300), other);
    assert.equal(sizedArtwork('https://image.tmdb.org/t/p/original/abc.jpg?v=2', 320), 'https://image.tmdb.org/t/p/w342/abc.jpg?v=2');
  });
});

describe('tracks (Kotlin Tracks.distinct / languageOf port)', () => {
  const t = (id: string, language: string | null, label: string | null, extra: Partial<TrackInfo> = {}): TrackInfo =>
    ({ id, language, label, channelCount: 0, isDefault: false, isForced: false, ...extra });
  test('languages from tags and names', () => {
    assert.equal(Tracks.languageOf(t('1', 'hun', null)), 'hu');
    assert.equal(Tracks.languageOf(t('1', null, 'Track 1 - [Italian]')), 'it');
    assert.equal(Tracks.languageOf(t('1', 'und', 'ITA AC3 5.1')), 'it');
    assert.equal(Tracks.languageOf(t('1', null, 'Commento per il cat')), null);
    assert.equal(Tracks.displayName(t('1', 'en', 'English SDH'), 0), 'Inglese · non udenti');
  });
  test('one entry per language, every language kept', () => {
    const hls = [t('a', 'it', 'Italiano', { channelCount: 2 }), t('b', 'en', 'English', { channelCount: 2 }), t('c', 'ita', 'Italiano', { channelCount: 6 })];
    assert.deepEqual(Tracks.distinct(hls).map((x) => x.id), ['c', 'b']);
    assert.deepEqual(Tracks.distinct(hls, 'a').map((x) => x.id), ['a', 'b']);
    const subs = [t('1', 'en', 'English'), t('2', 'en', 'English SDH'), t('3', 'en', 'English', { isForced: true }), t('4', 'en', 'English'), t('5', null, null), t('6', null, null)];
    assert.deepEqual(Tracks.distinct(subs).map((x) => x.id), ['1', '2', '3', '5', '6']);
  });
  test('"Sempre" shows something when the language is missing', () => {
    const always = { ...defaultPlayerSettings(), subtitleMode: 'ALWAYS' as const, preferredSubtitleLanguage: 'it' };
    assert.equal(Tracks.pickSubtitle([t('en', 'en', null), t('fr', 'fr', null, { isDefault: true })], always, ['it'], null)!.id, 'fr');
    assert.equal(Tracks.pickSubtitle([t('x', null, null)], always, ['it'], null)!.id, 'x');
  });
});

describe('XtreamAuthErrors (port of XtreamLoginErrorsTest.kt)', () => {
  test('wrong credentials in their many panel flavours', () => {
    assert.ok(XtreamAuthErrors.isRefused(401, '', true));
    assert.ok(XtreamAuthErrors.isRefused(403, '', false));
    for (const s of [400, 406, 410, 461]) assert.ok(XtreamAuthErrors.isRefused(s, '', true), `HTTP ${s}`);
    assert.ok(XtreamAuthErrors.isRefused(404, '{"user_info":{"auth":0}}', true));
    assert.ok(XtreamAuthErrors.isRefused(500, 'INVALID_CREDENTIALS', true));
    assert.ok(XtreamAuthErrors.isRefused(512, 'Invalid username or password', true));
    assert.ok(XtreamAuthErrors.isRefused(404, 'User not found', true));
    for (const b of ['', '[]', 'null', '{}']) assert.ok(XtreamAuthErrors.isEmptyLogin(b), `«${b}»`);
  });
  test('real server problems stay server errors', () => {
    for (const s of [500, 502, 503, 404, 429]) assert.ok(!XtreamAuthErrors.isRefused(s, '<html>Service unavailable</html>', true), `HTTP ${s}`);
    assert.ok(!XtreamAuthErrors.isRefused(400, '', false), 'a 400 on a list request is not about credentials');
    assert.ok(!XtreamAuthErrors.isEmptyLogin('{"user_info":{"auth":1}}'));
  });
});

describe('NetworkErrors (port of NetworkErrors.kt)', () => {
  test('HTTP statuses: same mapping and texts as fromStatus', () => {
    const cases: [number, 'SERVER' | 'LIST' | 'GUIDE', string, string][] = [
      [401, 'SERVER', 'INVALID_CREDENTIALS', 'Username o password non validi.'],
      [403, 'LIST', 'INVALID_CREDENTIALS', "Accesso negato (errore 403): il link della lista non è più valido o l'abbonamento è scaduto."],
      [401, 'GUIDE', 'INVALID_CREDENTIALS', "Accesso negato (errore 401): il link della guida TV non è più valido o l'abbonamento è scaduto."],
      [404, 'SERVER', 'NOT_FOUND', "Il server risponde, ma l'indirizzo non è quello giusto (errore 404). Controlla indirizzo e porta."],
      [410, 'LIST', 'NOT_FOUND', 'La lista non si trova più a questo indirizzo (errore 410): il link è sbagliato o scaduto.'],
      [404, 'GUIDE', 'NOT_FOUND', 'La guida TV non si trova più a questo indirizzo (errore 404).'],
      [429, 'LIST', 'TOO_MANY_REQUESTS', 'Il server della lista rifiuta le richieste perché sono troppe (errore 429). Aspetta qualche minuto e riprova.'],
      [503, 'SERVER', 'SERVER', "Il server del provider è temporaneamente fuori servizio (errore 503). Non dipende dall'app né dalla tua rete: riprova più tardi."],
      [502, 'GUIDE', 'SERVER', "Il server della guida TV è temporaneamente fuori servizio (errore 502). Non dipende dall'app né dalla tua rete: riprova più tardi."],
      [500, 'LIST', 'SERVER', "Il server della lista ha un problema interno (errore 500). Non dipende dall'app né dalla tua rete: riprova più tardi."],
      [302, 'SERVER', 'SERVER', 'Il server del provider ha risposto con un errore inatteso (codice 302). Riprova più tardi.'],
      [418, 'GUIDE', 'SERVER', 'Il server della guida TV ha risposto con un errore inatteso (codice 418). Riprova più tardi.'],
    ];
    for (const [code, target, kind, message] of cases) {
      const e = NetworkErrors.fromStatus(code, target);
      assert.ok(e instanceof XtreamException);
      assert.equal(e.kind, kind, `${code} ${target}`);
      assert.equal(e.message, message, `${code} ${target}`);
      assert.equal(e.httpStatus, code);
      assert.equal(e.isConnectivity, false);
    }
  });

  test('failed requests: offline, our timeouts, drops, anything else is unreachable', () => {
    const saved = NetworkErrors.status;
    try {
      NetworkErrors.status = () => 'OFFLINE';
      const offline = NetworkErrors.fromThrowable(new TypeError('Failed to fetch'), 'http://srv.example:8080', 'SERVER');
      assert.equal(offline.kind, 'NO_INTERNET');
      assert.equal(offline.message, 'Il dispositivo non è connesso a Internet. Controlla il Wi-Fi o la rete dati e riprova.');
      assert.equal(NetworkErrors.fromThrowable(new HttpError('x', 'timeout'), 'http://a.b', 'GUIDE').kind, 'NO_INTERNET', 'offline wins');

      NetworkErrors.status = () => 'UNKNOWN';
      const timeout = NetworkErrors.fromThrowable(new HttpError('Il server non risponde (tempo scaduto)', 'timeout'), 'http://srv.example', 'SERVER');
      assert.equal(timeout.kind, 'TIMEOUT');
      assert.equal(timeout.message, 'Il server del provider non risponde (tempo scaduto): potrebbe essere sovraccarico o offline. Riprova tra qualche minuto.');
      const dropped = NetworkErrors.fromThrowable(new HttpError('x', 'interrupted'), 'http://liste.example/l.m3u', 'LIST');
      assert.equal(dropped.kind, 'NETWORK');
      assert.equal(dropped.message, 'La connessione con il server della lista si è interrotta. Riprova.');
      for (const cause of [new HttpError('Connessione al server non riuscita'), new TypeError('Failed to fetch'), new Error('boh')]) {
        const e = NetworkErrors.fromThrowable(cause, 'https://user:pw@epg.example:8443/guide.xml?password=pw', 'GUIDE');
        assert.equal(e.kind, 'UNREACHABLE');
        assert.ok(e.isConnectivity);
        assert.equal(e.message,
          "Impossibile contattare il server della guida TV («epg.example:8443»): l'indirizzo potrebbe essere sbagliato, il server spento o la connessione assente.");
      }
      const already = new XtreamException('Username o password non validi.', 'INVALID_CREDENTIALS');
      assert.equal(NetworkErrors.fromThrowable(already, 'http://a.b', 'SERVER'), already);
    } finally {
      NetworkErrors.status = saved;
    }
  });

  test('which errors are connection failures', () => {
    assert.ok(NetworkErrors.isNetworkFailure(new HttpError('x', 'timeout')));
    assert.ok(NetworkErrors.isNetworkFailure(new TypeError('Failed to fetch')));
    assert.ok(!NetworkErrors.isNetworkFailure(new Error('QuotaExceededError')));
    assert.ok(!NetworkErrors.isNetworkFailure(new TypeError('x is not a function')));
  });

  test('hostOf: host, plus the port only when it is not the default one', () => {
    assert.equal(NetworkErrors.hostOf('http://srv.example:8080/player_api.php?username=u&password=p'), 'srv.example:8080');
    assert.equal(NetworkErrors.hostOf('http://srv.example:80/x'), 'srv.example');
    assert.equal(NetworkErrors.hostOf('https://srv.example:443'), 'srv.example');
    assert.equal(NetworkErrors.hostOf('https://srv.example:80/l.m3u'), 'srv.example:80');
    assert.equal(NetworkErrors.hostOf('http://user:secret@srv.example/l.m3u'), 'srv.example');
    assert.equal(NetworkErrors.hostOf('http://[::1]:8080/x'), '[::1]:8080');
    assert.equal(NetworkErrors.hostOf('srv.example/l.m3u?x=1'), 'srv.example');
    assert.equal(NetworkErrors.hostOf('u:p@srv.example:8080'), 'srv.example:8080');
  });
});

import type { CoreEvent } from '../api.ts';
import type { TextStreamOptions } from '../epg/byteStreams.ts';
import type { Http } from '../net/http.ts';
import type { Emitter } from '../util/emitter.ts';
import type { Db } from './db.ts';

/** What every repository needs (Kotlin: database + HttpClient + platform values). */
export interface Ctx {
  db: Db;
  http: Http;
  events: Emitter<CoreEvent>;
  /** Sent to the player in PlayableStream.headers (browsers can't send it on catalog requests). */
  defaultUserAgent: string;
  /** Like Android: live API Xtream streams use MPEG-TS, not HLS. */
  prefersHlsLive: boolean;
  textOptions: TextStreamOptions;
}

// Port of XtreamException (XtreamClient.kt). Also used for M3U lists and TV guides: the message is
// always the full, user-presentable explanation (see net/networkErrors.ts).

export type XtreamErrorKind =
  | 'INVALID_CREDENTIALS'
  | 'ACCOUNT_EXPIRED'
  /** Device offline, or connected to a network without Internet. */
  | 'NO_INTERNET'
  /** The address doesn't exist (DNS). */
  | 'HOST_NOT_FOUND'
  /** Server down, port closed or unreachable (in a browser also: any opaque fetch failure). */
  | 'UNREACHABLE'
  /** No answer in time. */
  | 'TIMEOUT'
  /** HTTPS certificate problem, or plain http blocked by the system. */
  | 'SECURE_CONNECTION'
  /** Other network failure (e.g. connection dropped midway). */
  | 'NETWORK'
  /** 404/410: wrong address/path or expired link. */
  | 'NOT_FOUND'
  /** 429. */
  | 'TOO_MANY_REQUESTS'
  /** 5xx or another unexpected status. */
  | 'SERVER'
  /** Answer that isn't what an Xtream server / M3U list sends. */
  | 'PARSE';

const CONNECTIVITY: XtreamErrorKind[] = ['NO_INTERNET', 'HOST_NOT_FOUND', 'UNREACHABLE', 'TIMEOUT', 'SECURE_CONNECTION', 'NETWORK'];

/** Kotlin Kind.isConnectivity: the request never got a usable answer. */
export function isConnectivity(kind: XtreamErrorKind): boolean {
  return CONNECTIVITY.indexOf(kind) >= 0;
}

export class XtreamException extends Error {
  readonly kind: XtreamErrorKind;
  /** HTTP status of the failed response, when there was one. */
  readonly httpStatus: number | null;
  constructor(message: string, kind: XtreamErrorKind, httpStatus: number | null = null) {
    super(message);
    this.name = 'XtreamException';
    this.kind = kind;
    this.httpStatus = httpStatus;
  }

  get isConnectivity(): boolean { return isConnectivity(this.kind); }
}

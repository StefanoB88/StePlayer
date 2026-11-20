// Browser objects hls.js needs (document, MediaSource, DOMParser...), provided by Amazon's polyfills on top of
// react-native-w3cmedia. Installed once, the first time an HLS stream opens.
import Document from '../vendor/hls/hlsjsplayer/polyfills/DocumentPolyfill';
import Element from '../vendor/hls/hlsjsplayer/polyfills/ElementPolyfill';
import TextDecoderPolyfill from '../vendor/hls/hlsjsplayer/polyfills/TextDecoderPolyfill';
import W3CMediaPolyfill from '../vendor/hls/hlsjsplayer/polyfills/W3CMediaPolyfill';
import MiscPolyfill from '../vendor/hls/hlsjsplayer/polyfills/MiscPolyfill';

let installed = false;

export function installHlsPolyfills(): void {
  if (installed) return;
  installed = true;
  const g = globalThis as { TextDecoder?: unknown };
  const ownDecoder = g.TextDecoder; // ours streams UTF-8 ({ stream: true }), which the shared core needs
  Document.install();
  Element.install();
  if (!ownDecoder) TextDecoderPolyfill.install();
  W3CMediaPolyfill.install();
  MiscPolyfill.install();
}

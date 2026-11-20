# StePlayer per Amazon Fire TV (Vega OS)

App React Native 0.83 per i Fire TV con Vega OS. Ha lo stesso ID dell'app Fire OS (`com.steplayer.app`), quindi stanno
sulla stessa scheda dell'Amazon Appstore.

- **Logica condivisa:** liste, guida TV, ricerca e sincronizzazione sono le stesse dell'app TV per Samsung e LG, cioè
  `../tizenApp/src/core` in TypeScript, caricato da Metro.
- **Riproduzione:** `@amazon-devices/react-native-w3cmedia`. HLS passa dalla versione di hls.js modificata da Amazon
  (modalità MSE); MP4, MKV, AVI e MPEG-TS vanno in modalità URL e li decodifica il sistema.
- **Dati:** IndexedDB (`fake-indexeddb`) salvato in `/data`, un file JSON per archivio (`src/platform/persist.ts`).
  Liste, catalogo, preferiti e progressi restano dopo il riavvio.
- **Adattamenti a Hermes/Vega** (`src/platform/polyfills.ts`):
  - `URL` completo, con `createObjectURL` per le sorgenti MSE;
  - `TextDecoder` in streaming, `structuredClone` e `DOMException`;
  - `clearTimeout` tollerante: su Vega lancia un'eccezione con i timer non validi e bloccava hls.js.

## Preparazione (una volta)
1. **Vega SDK**, con lo script ufficiale di Amazon (vedi la pagina "Install the Vega SDK"). Per disattivare la
   telemetria, in `~/vega/telemetry/config.json` imposta `"optIn": false`.
2. **hls.js per Vega:** `scripts/fetch_hlsjs.sh`. Scarica il pacchetto di Amazon, applica le patch a hls.js 1.5.11 e
   lo compila in `src/vendor/hls`. La cartella è esclusa da git: il wrapper di Amazon è proprietario e non va
   ridistribuito.
3. `npm install`

## Compilare e provare
```sh
scripts/build.sh            # dist/steplayer_{aarch64,armv7,x86_64}.vpkg
scripts/run.sh              # installa e avvia nel Vega Virtual Device (Mac con chip Apple: aarch64)
```
Gli strumenti Vega non accettano spazi nel percorso. Per questo `build.sh` copia l'app e il core condiviso in
`~/steplayer-vega-build` e compila lì.

Per lo sviluppo, in `src/devlog.ts` imposta `DEV_LOG_URL = 'http://10.0.2.2:8099'`. L'app manderà allora i log a un
server sul Mac e caricherà da sola la lista demo. In Release resta `null`.

## Pubblicare
- **Pacchetto per i Fire TV:** `dist/steplayer_armv7.vpkg`. Si carica nella stessa scheda dell'app Fire OS.
- **Ogni invio:** il numero di build cresce (`build-number` in `manifest.toml`).
- **Permessi nel manifest:** rete, riproduzione multimediale e tastiera a schermo (`com.amazon.inputmethod.service`).
  Senza quest'ultimo la tastiera non si apre.
- **Flussi `http://`:** `[network-traffic-policy.cleartext]` con `allowed-domains = ["*"]`. Senza, il lettore di Vega
  rifiuta ogni indirizzo non https ("Invalid or insecure URI scheme") e quasi nessun server Xtream funziona. La chiave
  non è nella documentazione Amazon che ho trovato: provata nel Vega Virtual Device (SDK 2.0), da ricontrollare se
  la revisione Amazon la contesta.

## Stato
- **Provato nel Vega Virtual Device:**
  - avvio e salvataggio dei dati;
  - Home, Live, Film, ricerca, pagina film;
  - canali HLS con cambio canale, film MP4, ripresa dal punto salvato;
  - film MP4 da un server `http://`;
  - ricerca nelle sezioni Live/Film/Serie con la tastiera Amazon, Back che svuota la ricerca;
  - schermate con dati estremi (titoli lunghissimi, 30 categorie, 14 stagioni, trame lunghe): niente tagliato;
  - errore con "Riprova" quando un video non parte.
- **Da verificare su un Fire TV vero:**
  - flussi `.ts` diretti;
  - sottotitoli e tracce audio;
  - liste molto grandi (velocità di salvataggio in `/data`).

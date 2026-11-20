# StePlayer per TV Samsung (Tizen) e LG (webOS)

App web Tizen con la stessa grafica e le stesse funzioni delle app Android TV e iOS, pensata per il telecomando.
Funziona sui televisori Samsung dal 2018 in poi (Tizen 4.0 e successivi). Si installa senza store, in modalità sviluppatore.

## Cosa c'è
- Accesso al server (indirizzo, username e password) e liste M3U (con guida TV XMLTV).
- Home, Live con guida TV e replay, Film, Serie, ricerca intelligente, impostazioni.
- Player Samsung AVPlay (decodifica hardware: HLS, TS, MP4, MKV), cambio canale con frecce, tasti CH e numeri, elenco canali, audio e sottotitoli, timer di spegnimento, "Stai ancora guardando?".
- Preferiti: pressione lunga su OK (oppure tasto rosso) su un canale.
- I download non sono disponibili su TV (come su Android TV e Apple TV).

## Installare sul televisore (una volta sola)
1. **Modalità sviluppatore sulla TV.** Apri *App*, digita `1 2 3 4 5` col telecomando, attiva *Developer mode*, inserisci l'IP di questo Mac e **riavvia** la TV (tieni premuto il tasto di accensione finché si riaccende). Annota l'IP del televisore (*Impostazioni → Generali → Rete → Stato rete*).
2. **Estensione "Tizen Extension" per VS Code.** Installa gli strumenti Samsung (`sdb`, `tz`) da sola. Su Mac con chip Apple serve Rosetta (`softwareupdate --install-rosetta --agree-to-license`).
3. **Certificato Samsung.** Nel *Certificate Manager* dell'estensione crea un certificato **Samsung** per TV (account Samsung, televisore collegato per il DUID) e chiama il profilo `StePlayer`.

## Installare o aggiornare l'app
```sh
npm install                    # la prima volta
scripts/tv.sh 192.168.50.144   # IP del televisore
```
Lo script compila, firma con il profilo `StePlayer` (`dist/StePlayer.wgt`), installa e avvia StePlayer.
Il certificato vale un anno: dopo la scadenza va rinnovato e l'app reinstallata.

## TV LG (webOS)
Lo stesso codice gira sulle TV LG con webOS 4.0 e successivi: la piattaforma viene riconosciuta all'avvio. ID app
`com.steplayer.app`; manifest, pagina, icone e splash sono in `webos/`.
```sh
npm run build:webos      # build-webos/ (bundle per Chromium 53)
npm run package:webos    # due pacchetti: dist/webos-uhd/ (1920×1080) e dist/webos-fhd/ (1280×720)
scripts/webos.sh         # crea i pacchetti, installa quello UHD sulla TV in modalità sviluppatore e lo avvia
scripts/webos.sh tv fhd  # installa quello per le TV Full HD
```
LG vuole due pacchetti: le TV Full HD hanno la grafica delle app a 1280×720. Il pacchetto `webos-fhd` è la stessa
app con `"resolution": "1280x720"` e l'interfaccia ridotta a 2/3 (`<html class="r720">`).
Su LG il player usa il `<video>` di sistema, che decodifica in hardware. Sono supportati il puntatore, l'OK e la
rotellina del Magic Remote. Il tasto Indietro (461), sulla Home, torna al launcher. Materiali per il Seller Lounge e
checklist: `../store/lg/`.

## Sviluppo
```sh
npm run build        # build/ (bundle per Chromium 56)
npm run dev          # server locale con dati di prova (nomi inventati)
npm test             # test della logica
npm run typecheck
node dev/shot.mjs --fresh "wait:3000;shot:home"   # schermate in Chrome headless
```
Lista di prova: `http://127.0.0.1:8080/dev/list.m3u`. Server di prova (Accesso al server): `http://127.0.0.1:8080`, utente `demo`, password `demo`.

Struttura: `src/core` = logica portata 1:1 dal modulo Kotlin `shared` (stessi algoritmi e testi); `src/ui` = interfaccia TV
(navigazione a zone col telecomando, liste virtualizzate); `static` = `config.xml`, `index.html`, stili e icona.

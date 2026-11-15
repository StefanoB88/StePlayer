# StePlayer

Lettore multimediale nativo (liste M3U/HLS) per **Android (telefono, tablet, Android TV / Fire TV)** e **iOS / iPadOS / tvOS**.
Supporta l'**accesso al server** (login con indirizzo, username e password) e le **liste M3U/M3U8**, con EPG, catch-up, preferiti, "continua a guardare",
download di film ed episodi, Chromecast (Android) e AirPlay (Apple).

> StePlayer è solo un lettore: non fornisce, ospita né vende contenuti. Gli utenti inseriscono
> le proprie liste sotto la propria responsabilità.

## Architettura

```
StePlayer/
├── shared/        Kotlin Multiplatform: tutta la logica (una sola volta per entrambe le piattaforme)
│   ├── xtream/    client player_api.php tollerante ai server con risposte irregolari, URL live/VOD/serie/timeshift
│   ├── m3u/       parser M3U in streaming + classificazione Live / Film / Serie
│   ├── epg/       parser XMLTV in streaming (anche .gz), import filtrato sul DB
│   ├── data/      repository: liste, catalogo, EPG, libreria, download, risoluzione stream
│   └── sqldelight database locale (SQLite) con indici per liste e ricerca veloci
├── androidApp/    Jetpack Compose + Media3/ExoPlayer + WorkManager + Cast
└── iosApp/        SwiftUI (iOS + tvOS) + AVPlayer + VLCKit (LGPL 2.1) + URLSession in background
```

| Livello | Android | iOS / tvOS |
|---|---|---|
| UI | Jetpack Compose (Material 3, TV con D-pad) | SwiftUI (`@Observable`) |
| Player | Media3 ExoPlayer (HLS, TS, MKV, MP4) | AVPlayer (HLS/MP4) + fallback VLCKit per MKV/AVI/TS |
| Casting | Google Cast (media3-cast) | AirPlay |
| Download | WorkManager + notifica in primo piano, resume via HTTP Range | URLSession in background |
| Credenziali | Android Keystore (AES-256-GCM) | Keychain (solo su questo dispositivo) |
| Logica, rete, DB | modulo `shared` (Ktor, SQLDelight, kotlinx.serialization) | lo stesso modulo, come framework `Shared` |

### Prestazioni
- I cataloghi dei server (anche 50k+ film) vengono scaricate in parallelo e salvate in un'unica transazione per sezione.
- M3U ed EPG vengono letti **in streaming**: niente file interi in memoria, anche su box TV economici.
- L'EPG salva solo i programmi dei canali presenti nella lista, in una finestra di −7/+7 giorni.
- La ricerca globale usa indici full-text SQLite (FTS4 senza copia del testo, uno per lista).

### Ricerca globale intelligente (`shared/search/`)
Una sola barra cerca **canali, film, serie (anche nella trama) e tutta la guida TV**. Cercando
"Napoli" escono il programma in onda ora, quelli dei prossimi giorni, i replay disponibili,
i canali con "Napoli" nel nome e i film e le serie ambientati a Napoli.
- Ignora accenti e maiuscole e accetta più parole in qualsiasi ordine ("golfo napoli").
- Riconosce le varianti italiane: "napoli" trova anche "napoletano".
- Corregge i refusi usando il vocabolario dell'indice ("nappoli" → "Forse cercavi: napoli").
- Accetta il numero del canale ("201").
- Ordina per rilevanza (titolo esatto > parola intera > prefisso > trama o descrizione), con
  priorità a ciò che è in onda ora.
- Accorpa le repliche ("+6 repliche") e mostra i programmi passati solo se il canale ha il catch-up.
- Salva le ricerche recenti.

### Sicurezza e privacy
- Nessun server, nessun account, nessuna analytics: tutto resta sul dispositivo.
  L'unica richiesta verso terzi è facoltativa e parte solo se l'utente avvia "Test connessione":
  un download anonimo dal server di test pubblico di Cloudflare, per confrontare la velocità
  della rete di casa con quella del provider (nessun dato personale inviato).
- Le password dei server e gli URL M3U (che spesso contengono token) **non** finiscono nel database:
  restano nel Keystore o nel Keychain. Gli URL di riproduzione vengono costruiti al momento.
- Backup cloud disattivato per i dati sensibili. Gli URL non vengono mai scritti nei log.
- Molti server di streaming usano solo `http`: l'app consente il traffico in chiaro (Android
  `network_security_config`, iOS ATS). Su App Store va fornita una giustificazione in revisione.

## Build

Requisiti: JDK 17+ (va bene la JBR di Android Studio), Android SDK, Xcode 16+, `brew install xcodegen`.

```bash
# Test della logica condivisa (JVM + simulatore iOS)
./gradlew :shared:jvmTest :shared:iosSimulatorArm64Test

# Android
./gradlew :androidApp:assembleDebug

# iOS / tvOS (VLCKit: lo scarica scripts/fetch_vlckit.sh, eseguito anche dal pre-action degli schemi)
cd iosApp && ./scripts/fetch_vlckit.sh && xcodegen && open StePlayer.xcodeproj
```

## Note per la pubblicazione sugli store
- Non includere liste, URL o loghi di canali di esempio; screenshot solo con contenuti di cui hai i diritti
  (es. stream di test pubblici o Big Buck Bunny).
- Descrizione dello store: "lettore multimediale per le tue liste". Evita riferimenti a canali o
  contenuti a pagamento.
- Apple (linea guida 5.2.3) e Google sono severi con i lettori di liste: disclaimer ben visibile al primo avvio
  (già presente) e nessuna funzione che faciliti l'accesso a contenuti non autorizzati.

# Amazon Appstore (Fire TV) — materiali

## Fire OS (Android)
Su Fire OS va la stessa app di Android TV: ID `com.steplayer.app`, stesso APK. Fire TV viene riconosciuto come TV
(`amazon.hardware.fire_tv`), quindi l'app usa l'interfaccia per il telecomando. Chromecast e download sono spenti,
come su Android TV.
- **Fire OS supportati:** Fire OS 7 e 8 (Android 9 e successivi). Fire OS 6 resta escluso perché `minSdk` è 26.
- **APK:** `./gradlew :androidApp:assembleRelease` → `androidApp/build/outputs/apk/release/androidApp-release.apk`.
  È firmato con la chiave di caricamento; Amazon lo firma di nuovo con la propria chiave.
- **Architetture:** `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`. Molti Fire TV eseguono le app a 32 bit.

| Campo dell'Appstore | File |
| --- | --- |
| Small icon 114×114 | `icon-114x114.png` |
| Large icon 512×512 | `icon-512x512.png` |
| Fire TV app icon 1280×720, senza trasparenza | `firetv-icon-1280x720.png` |
| Screenshot Fire TV 1920×1080 | `screenshots/tv-1…4-*.jpg`: le stesse schermate di Android TV, con dati inventati |
| Fire TV background 1920×1080 | `firetv-background-1920x1080.png`: il logo sta a destra, perché a sinistra Amazon mette i testi |
| Promotional image 1024×500 | `promo-1024x500.png` |

Le immagini si rigenerano con `node scripts/brand/make-brand.mjs amazon`.

**Testi della scheda:** gli stessi di `../google-play-listing.md`. Al posto di "Android TV" scrivi "Fire TV", e niente
marchi di terzi né la parola "IPTV".

**Note per la verifica (Testing instructions):**
```
StePlayer is a neutral media player: it ships with no channels or content. To test:
1. Launch the app, tick the confirmation and press "Ho capito e accetto".
2. Choose "Inserisci con il telecomando" → "Lista M3U" and type this URL (Blender Foundation films under
   Creative Commons licenses only): https://stefanob88.github.io/steplayerwebsite/demo.m3u
3. Press "Salva e connetti", then open "Live" or "Film" and play an item.
No login is needed. No ads, no in-app purchases, no data collection.
```

## Vega OS
App React Native separata in `vegaApp/`: vedi il README di quella cartella.

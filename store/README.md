# Pubblicazione negli store — materiali e controlli

Materiali per App Store, Google Play e Samsung Seller Office. Niente di questa cartella è incluso nelle app.

## File
- `demo-list.m3u` — lista dimostrativa **solo per i revisori e per gli screenshot**: solo film della Blender
  Foundation con licenza Creative Commons. Copia identica di quella pubblicata su https://stefanob88.github.io/steplayerwebsite/demo.m3u
  (repo `steplayerwebsite`). Le app restano vuote al primo avvio: nessuna lista precaricata.
- `app-store-review-notes.md` — testo per le App Review Notes (Apple).
- `google-play-listing.md` — titolo, descrizione breve e completa senza marchi di terzi.
- `samsung-test-information.md` — campo "Test Information" del Seller Office.
- `samsung-app-description.md` — contenuto per il modello "App Description" di Samsung.
- `samsung/` — immagini per il Seller Office (icona, logo, sfondi, screenshot), vedi `samsung/README.md`.
- `privacy-policy.md`, `terms.md` — riassunti; i testi completi (IT + EN) sono pubblicati su
  https://stefanob88.github.io/steplayerwebsite/privacy.html e https://stefanob88.github.io/steplayerwebsite/terms.html. Contatto per privacy e segnalazioni: `stehelp@gmail.com`.

## Screenshot
Solo con `demo-list.m3u` o con video tuoi: mai nomi di canali reali, locandine di film o eventi sportivi, nemmeno
sfocati. Nomi di categoria neutri ("Demo HLS", "Film aperti").

## Stato dei controlli (verificati nel codice)
- Nessun server dello sviluppatore: liste e credenziali restano sul dispositivo (Android: database locale + Keystore;
  iOS/tvOS: database locale escluso dai backup + Portachiavi "solo questo dispositivo", senza iCloud; TV Samsung: IndexedDB dell'app). Nessun portale web, nessun MAC address,
  nessun ID dispositivo.
- Passaggio telefono → TV solo in rete locale, diretto, con codice monouso di 4 cifre, scadenza 10 minuti,
  blocco dopo 5 codici errati. Nessun relay o servizio esterno.
- Loghi ed EPG letti solo dalla lista dell'utente (`tvg-logo`, `url-tvg`) o dal suo server. Nessun catalogo di loghi
  o guide ospitato dallo sviluppatore. Nessuna API TMDB o simili.
- Nessun SDK di statistiche, pubblicità o tracciamento. User-Agent onesto ("StePlayer/…").
- Nessun pagamento, prezzo o link di acquisto in nessuna app.
- Avviso legale obbligatorio al primo avvio (3 punti) con "Ho capito e accetto", attivo solo dopo la spunta.
- TV Samsung: tasto RETURN gestito (Home → uscita diretta a Smart Hub, voce 13 della checklist), salvaschermo disattivato solo durante la
  riproduzione, avviso quando la rete è assente, errori di rete con nuovi tentativi automatici.

# LG Content Store (webOS TV) — materiali

L'app per TV LG usa lo stesso codice dell'app Samsung (`tizenApp/`): la piattaforma viene riconosciuta all'avvio.
- **Identificativo:** ID app `com.steplayer.app`, versione 1.0.0.
- **Televisori supportati:** webOS 4.0 e successivi (TV del 2018 in poi).

## File
| File | A cosa serve |
| --- | --- |
| `tizenApp/dist/webos-uhd/com.steplayer.app_1.0.0_all.ipk` | Pacchetto per le TV Ultra HD, grafica a 1920×1080 (`npm run package:webos`) |
| `tizenApp/dist/webos-fhd/com.steplayer.app_1.0.0_all.ipk` | Pacchetto per le TV Full HD, grafica a 1280×720: stessa app, interfaccia ridotta a 2/3 |
| `self-checklist.md` | Risultati della Self-Checklist 5.0, da copiare nel modello Excel di LG |
| `ux-scenario.md` | Documento "UX scenario" richiesto dal Seller Lounge |
| `test-information.md` | Testo per il campo dei commenti per il QA |
| `images/screenshot-1…5-1920x1080.jpg` | Screenshot per lo store, JPG 1920×1080 (170–275 KB): Home, Live, Guida TV, Film, Serie |
| `images/icon-400x400.png` | Icona quadrata per lo store, PNG 400×400 |
| `tizenApp/webos/icon.png` (80×80), `largeIcon.png` (130×130) | Icone dell'app, già incluse nel pacchetto |

Gli screenshot vengono dalla build LG con il catalogo di prova: nomi e immagini inventati, nessun titolo o logo reale.
Si rigenerano con `tizenApp/dev/lg-store-assets.mjs`; le istruzioni sono in testa al file. Icone e splash si
rigenerano con `node scripts/brand/make-brand.mjs webos`.

## Due pacchetti (UHD e FHD)
Nel Seller Lounge, nella scheda File Upload, si caricano entrambi i pacchetti: quello a 1920×1080 vale per i modelli
UHD, quello a 1280×720 per i modelli FHD. Senza il secondo, l'app non viene pubblicata sulle TV Full HD.

## Differenze rispetto a Samsung
- **Player:** si usa il `<video>` di sistema di webOS, con decodifica hardware di HLS, MPEG-TS e MP4. Tracce audio e
  sottotitoli si possono scegliere quando il flusso li espone.
- **Magic Remote:** il puntatore resta visibile (su Samsung il cursore è nascosto), evidenzia l'elemento sotto di sé,
  l'OK lo attiva e la rotellina scorre liste e pagine.
- **Tasto Indietro (461):** sulla Home torna al launcher, come prevede LG.
- **IP della TV** per l'abbinamento col telefono: si legge con il servizio Luna `com.palm.connectionmanager/getStatus`.
  Per questo in `appinfo.json` c'è `requiredACG: ["network.query"]`.
- **Termini:** al primo avvio c'è anche "Non accetto", che chiude l'app (voce 18 della checklist). Vale anche su Samsung.

## Provare sulla TV
1. Sul televisore installa **Developer Mode** dal Content Store. Accedi con l'account sviluppatore LG e attiva
   *Dev Mode Status* e *Key Server*.
2. Registra la TV sul Mac. La passphrase è quella mostrata nell'app Developer Mode:
   ```sh
   cd tizenApp
   npx ares-setup-device --add tv --info "{'host':'IP_DELLA_TV','port':'9922','username':'prisoner'}"
   npx ares-novacom --device tv --getkey
   ```
3. Avvia `scripts/webos.sh`: compila, crea il pacchetto, lo installa e apre StePlayer.

La modalità sviluppatore scade dopo 50 ore: si rinnova dall'app Developer Mode.

## Prima di inviare
Le voci della checklist segnate "TV" vanno verificate su un televisore vero: tastiera, riavvio, rete, riproduzione e
sottotitoli. Lo sviluppo è stato provato solo in Chromium, simulando webOS.

# StePlayer — App Description (content for the Samsung template)

Copy these sections into Samsung's App Description document template (checklist items 1, 5 and 18: the reviewers
compare every menu of the app with this document).

## Overview
StePlayer is a neutral media player for M3U lists and for the user's own media server. It ships with no channels,
lists or content: the user adds a list they have the rights to. All data stays on the TV. No account, no payments.

- Supported models: Samsung Smart TV, Tizen 4.0 and later (2018+).
- Languages: Italian.
- Input: remote control (mouse not required).
- Test data: M3U list `https://stefanob88.github.io/steplayerwebsite/demo.m3u` (Blender Foundation films under
  Creative Commons licenses only). The "Accesso al server" option needs the user's own server and is optional: every
  feature can be tested with the M3U list.

## Screens and menus
1. **First launch.** Legal notice with 3 points. The user ticks the confirmation and presses "Ho capito e accetto".
2. **Aggiungi lista.**
   - "Usa il telefono": receives the list from the StePlayer phone app on the same Wi-Fi, with a 4-digit code.
   - "Inserisci con il telecomando":
     - "Lista M3U": name, list URL, optional guide URL.
     - "Accesso al server": address, username, password.
   - "Salva e connetti" downloads the list; a progress screen is shown.
3. **Side menu (rail):** Cerca, Home, Live, Film, Serie, Impostazioni.
4. **Home.** Featured item ("Guarda ora", "Dettagli"), "Continua a guardare", recent channels, recently added films
   and series.
5. **Live.**
   - Categories (Preferiti, Tutti, list categories) and channels with the programme now/next.
   - The guide button (top right) opens the TV guide.
   - OK plays; a long press of OK or the red key adds/removes a favourite.
6. **Film / Serie.**
   - Category chips and a poster grid.
   - The detail page has "Riproduci" / "Riprendi", favourite, "Trama completa" (full text in a scrollable window) and
     seasons and episodes.
7. **Cerca.** On-screen keyboard; results across channels, films, series and the guide.
8. **Player.**
   - Play/Pause, FF/RW (±30 s), seek bar with time.
   - Audio and subtitle tracks, aspect ratio, sleep timer.
   - Live: CH+/CH− and number keys change the channel, Left opens the channel list, Info shows the banner.
   - The screen saver is disabled while a video is playing.
9. **Impostazioni.**
   - Lists: add, edit, delete, refresh.
   - Playback: buffer, subtitles style.
   - Expiry reminders.
   - Legal notes (neutrality statement, privacy, link to the privacy policy and terms), version.

## Remote keys
| Key | Action |
| --- | --- |
| Arrows / OK | Navigation / select (long OK: favourite) |
| RETURN | Back to the previous page. On Home: exit to Smart Hub. In the player: close the player. |
| EXIT | Exit the app (system) |
| Play, Pause, Play/Pause, Stop, FF, RW | Player controls |
| CH+ / CH−, 0–9 | Change channel (Live) |
| Red | Favourite (Live list) |
| Info | Player banner/controls |
| Volume, Mute | System (the app never changes the volume or the mute state) |

## Network
- If the network drops, a banner says "Connessione assente" and data requests show an error with "Riprova".
- During playback the player shows the error and retries automatically; it resumes when the network returns.
- Return and Exit keep working while offline.

## Privacy and legal
- Privacy policy: https://stefanob88.github.io/steplayerwebsite/privacy.html
- Terms: https://stefanob88.github.io/steplayerwebsite/terms.html
- Contact: stehelp@gmail.com

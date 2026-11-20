# StePlayer — LG webOS TV Self-Checklist (v5.0, 2022-09-27)

App ID `com.steplayer.app`, version 1.0.0, webOS TV 4.0 and later (1920×1080). Copy each result into the official
Excel template from the Seller Lounge.

StePlayer is a neutral media player. It ships with no content and plays only the M3U list or personal server that the
user enters. The QA test list is in `test-information.md`: Creative Commons films by the Blender Foundation.

How to read the "Verified" column:
- **Emulated**: checked by the developer in Chromium with webOS stubs (Magic Remote pointer, wheel, keys 461/13).
- **TV**: must be confirmed on a real LG TV before submission.

Every item must end as Pass or N/A. Fill in "Fail" only if the TV test fails, and fix the issue before submitting.

| No | Test item | Result | Verified | Notes |
| --- | --- | --- | --- | --- |
| 1 | Execution | Pass | Emulated, TV | Starts on the terms screen (first launch) or on Home. |
| 2 | Main page | Pass | Emulated, TV | Splash `splash.png`, then the UI. All buttons, text and logos sit within the overscan frame (≥ 60 px from the edges). |
| 3 | Reboot | Pass | TV | Lists and settings are stored on the TV (IndexedDB). After a power or AC cycle the app opens on Home; playback starts again from the content page. |
| 4 | Advertisement | N/A | — | No advertising. |
| 5 | Resolution | Pass | Emulated, TV | Fixed 1920×1080 layout, 16:9. |
| 6 | Correct text | Pass | Emulated | Long titles end with an ellipsis. Long descriptions open in a scrollable dialog with a scroll bar. |
| 7 | Mouse over / focus | Pass | Emulated | Idle, focused (orange ring and scale) and selected (filled) states are distinct. Pointer hover and remote focus use the same style. |
| 8 | Flickering | Pass | TV | No flicker when opening or playing content. |
| 9 | Full size | Pass | TV | 16:9 video fills the screen. "Formato" offers Adatta / Riempi / Zoom. |
| 10 | UI button | Pass | Emulated | Every button works as described in the UX scenario. |
| 11 | BACK button | Pass | Emulated | Optional on webOS. The on-screen back arrow (player, detail pages) does the same as the remote BACK key. |
| 12 | EXIT button | N/A | — | No EXIT UI button. Refusing the terms ("Non accetto") leaves the app to the launcher. |
| 13 | LockUp / LatchUp | Pass | TV | — |
| 14 | Abnormal end | Pass | TV | Network loss shows a banner and playback retries automatically. Check on wired and Wi-Fi, with static and DHCP addresses. |
| 15 | Keyboard | Pass | TV | Text fields open the LG virtual keyboard (standard `<input>`), including upper/lower case and cursor keys. |
| 16 | Keyboard | Pass | TV | The typed text, including `\`, `/`, `:` and `.`, is used as entered (list URLs). |
| 17 | Keyboard | Pass | TV | Voice input of the LG keyboard writes into the field. |
| 18 | Terms | Pass | Emulated | First launch shows the terms. "Ho capito e accetto" (enabled after the confirmation tick) continues; "Non accetto" leaves the app. |
| 19 | Sign Up | N/A | — | No membership. |
| 20 | Sign In | N/A | — | No StePlayer account. The optional "Accesso al server" stores the address of the user's own server, like an M3U list URL. Every feature can be tested with the M3U list. |
| 21 | Sign Out | N/A | — | See 20. |
| 22 | Search | Pass | Emulated | "Cerca" searches channels, films and series. A word with no match shows an empty-result message. |
| 23 | Adult authentication | N/A | — | The app provides no content and no adult category. |
| 24 | Payment | N/A | — | No in-app purchases. |
| 25 | Payment | N/A | — | No in-app purchases. |
| 26 | General remote control | Pass | Emulated, TV | Arrows, OK, BACK, numbers, CH+/CH−, ▶/❚❚/■/◀◀/▶▶, red, INFO. |
| 27 | Magic Remote control | Pass | Emulated, TV | Pointer, OK, wheel and keys all supported. |
| 28 | MMRC / Pointer | Pass | Emulated | The item under the pointer is highlighted; the arrows continue from it. |
| 29 | MMRC / OK key | Pass | Emulated | OK with the pointer activates the item under it, once. |
| 30 | MMRC / Wheel | Pass | Emulated | The wheel scrolls lists, grids and pages one item per notch. In the live player it changes channel. |
| 31 | Navigation key | Pass | Emulated | — |
| 32 | Function (colour) key | Pass | Emulated | Red: add or remove a favourite channel (Live). Other colour keys are not used. |
| 33 | General / OK key | Pass | Emulated | Short press activates. Long press on a channel toggles the favourite. |
| 34 | Support only MMRC / Basic | N/A | — | The app supports the general remote too. |
| 35 | Support only MMRC / Non basic | N/A | — | See 34. |
| 36 | HOME key | Pass | TV | System behaviour: the Home launcher bar appears. |
| 37 | BACK key | Pass | Emulated | BACK returns one level at a time. On Home it goes back to the launcher (`PalmSystem.platformBack`). In the player it first closes panels, then the player. |
| 38 | EXIT key | Pass | TV | System behaviour: the app closes and Live TV appears. |
| 39 | LIVE key | Pass | TV | System behaviour. |
| 40 | Other key | Pass | Emulated | Number keys type a channel number in the live player. Unused keys are ignored. |
| 41 | Change language | N/A | — | Italian only, no language setting. |
| 42 | Sound | N/A | — | No background music or sound effects (only the audio of the played video). |
| 43 | Play contents | Pass | TV | The TV's media pipeline plays HLS (adaptive) and MP4 from the test list. Check at 512 kbps to 17.5 Mbps and on IPv6. |
| 44 | Full / Original screen | Pass | TV | Player → "Formato": Adatta (original ratio), Riempi (full screen), Zoom. |
| 45 | Playback control | Pass | Emulated, TV | Play/pause, −10/+10 s, seek bar (arrows, or pointer click on the bar), ◀◀/▶▶ ±30 s, ■ closes the player. Pointer and keys behave the same. |
| 46 | Replay | Pass | TV | After a film ends it can be played again from its page. |
| 47 | Real time | Pass | TV | Live HLS channels of the test list. |
| 48 | Subtitles | Pass | TV | Subtitle tracks exposed by the stream can be chosen. Size, colour and background are applied. |
| 49 | Play resume | Pass | Emulated | The film page offers "Riprendi da …" after a partial viewing. |
| 50 | Contents resolution | Pass | TV | SD, HD and FHD; UHD where the TV supports it. |
| 51 | Contents codec | Pass | TV | Codecs of the TV's media pipeline (H.264, HEVC, AAC, AC-3…). |
| 52 | Contents DRM | N/A | — | No DRM or paid content. |
| 53 | Factory reset | Pass | TV | After a factory reset the app installs and starts normally (first-launch terms). |

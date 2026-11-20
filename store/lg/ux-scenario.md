# StePlayer — UX Scenario (LG webOS TV)

StePlayer is a neutral media player for the user's own M3U/M3U8 lists and personal media server. It ships with no
channels or content. UI language: Italian. Resolution: 1920×1080. Supported remotes: Magic Remote (pointer, wheel,
OK, keys) and the general remote.

## 1. Screen flow

```
Launch ─► Splash ─► [first launch] Terms ──"Non accetto"──► leaves the app (launcher)
                               │
                               └─"Ho capito e accetto"─► Add list ─► Sync dialog ─► Home
[later launches] ───────────────────────────────────────────────────────────────► Home

Home (side rail: Cerca · Home · Live · Film · Serie · Impostazioni)
 ├─ Live ─► channel list + programme guide ─► Player (live)
 ├─ Film ─► poster grid ─► Film page ─► Player (film)
 ├─ Serie ─► poster grid ─► Series page (seasons, episodes) ─► Player (episode)
 ├─ Cerca ─► results (channels, films, series)
 └─ Impostazioni ─► lists, playback, subtitles, legal notes
```

## 2. Screens

| Screen | Content | Main actions |
| --- | --- | --- |
| Terms | The three legal points, privacy note, confirmation tick | Tick → "Ho capito e accetto" (continue); "Non accetto" (leave the app) |
| Add list | "Usa il telefono" (code from the StePlayer phone app, same Wi-Fi) or "Inserisci con il telecomando": "Lista M3U" (name, list URL, optional guide URL) or "Accesso al server" | Text fields open the LG virtual keyboard; "Salva e connetti" |
| Sync dialog | Progress of the list download | Waits; on error shows the cause, with "Riprova" and "Chiudi" |
| Home | Featured item, rows of recent films, series, favourite channels | OK opens; "Aggiorna" refreshes the list |
| Live | Categories, channel list with current programme, guide | OK plays; red key or long OK = favourite |
| Film / Serie | Category chips and poster grid | OK opens the page |
| Film / Series page | Plot (full text in a scrollable dialog), "Guarda ora" / "Riprendi da …", favourite, episodes | OK plays |
| Player | Video full screen; controls appear on any key or pointer movement and hide after 4 s | See section 3 |
| Search | On-screen keyboard field, results by type | OK opens a result |
| Settings | Lists (add, edit, delete), playback buffer, subtitle style, legal notes | — |

## 3. Player controls

| Control | UI button | Remote key |
| --- | --- | --- |
| Play / pause | Centre button | OK (controls shown), ▶ / ❚❚ |
| Back 10 s / forward 10 s (film) | ↺10 / ↻10 | ◀ / ▶ (controls hidden) |
| Back 30 s / forward 30 s (film) | — | ◀◀ / ▶▶ |
| Seek (film) | Seek bar: arrows move the preview, OK commits; pointer click jumps | — |
| Next / previous channel (live) | Channel list (☰) | CH+ / CH−, ▲ / ▼, wheel, number keys |
| Audio, subtitles, picture format, options | Top-right icons | — |
| Close the player | ← (top left) | BACK, ■ |

## 4. Remote control keys

| Key | Behaviour |
| --- | --- |
| Arrows / OK | Move the focus, activate |
| BACK | One level back; on Home it returns to the launcher |
| HOME / EXIT / LIVE | System behaviour (launcher, Live TV) |
| CH+ / CH−, 0–9 | Change channel in the live player |
| Red | Add or remove a favourite channel |
| ▶ ❚❚ ■ ◀◀ ▶▶ | Player controls |
| Magic Remote pointer | Highlights the item under it; OK activates it; the wheel scrolls lists one item per notch |

## 5. Other

- **Sound:** no background music or sound effects.
- **Adult content:** none; the app provides no content.
- **Payments:** none.
- **DRM:** none.
- **Accounts:** none.
- **Network loss:** a banner on screen; playback retries by itself when the connection returns.
- **Data:** lists, favourites and history stay on the TV. There is no developer server and no tracking.

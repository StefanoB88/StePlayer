# LG Seller Lounge — Comments for QA

StePlayer is a neutral media player. It ships with no channels or content. To test:

1. Launch the app, tick the confirmation and select "Ho capito e accetto". "Non accetto" leaves the app.
2. Choose "Inserisci con il telecomando" → "Lista M3U" and type this URL in "URL lista". The list contains only
   Blender Foundation films under Creative Commons licenses:
   https://stefanob88.github.io/steplayerwebsite/demo.m3u
   Alternatively, "Usa il telefono" receives the list from the StePlayer phone app on the same Wi-Fi.
3. Select "Salva e connetti", then open "Live" (HLS streams) or "Film" (MP4) and play an item.

Keys and pointer:
- BACK goes back one level; on Home it returns to the launcher.
- CH+/CH−, the number keys and the wheel change channel in the live player.
- A long OK press or the red key adds a favourite channel.
- The Magic Remote pointer, OK and wheel work on every screen.

No login is needed to test. The optional "Accesso al server" is only for the user's own server, and every feature
can be tested with the demo M3U list.

There is no adult content, no payment and no DRM. Network errors are shown on screen and playback retries
automatically.

Privacy policy: https://stefanob88.github.io/steplayerwebsite/privacy.html
Terms of use: https://stefanob88.github.io/steplayerwebsite/terms.html

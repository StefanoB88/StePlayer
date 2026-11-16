# Google Play Console — images

All screenshots come from the release APK on Android emulators, with the fictional dev catalogue (invented names,
generated artwork) and a clean status bar. JPEG, no alpha, as Play requires. Regenerate with
`scripts/store/shots-android.py` (emulator only) and `node scripts/brand/make-brand.mjs play`.

| Play Console field (Store presence → Main store listing) | Files | Format |
| --- | --- | --- |
| App icon | `icon-512.png` | 512×512 PNG |
| Feature graphic | `feature-graphic-1024x500.png` | 1024×500 PNG, no alpha |
| Phone screenshots (2–8) | `phone/phone-1…4.jpg` | 1080×1920 (9:16) |
| 7-inch tablet screenshots | `tablet-7/tablet7-1…4.jpg` | 1200×1920 |
| 10-inch tablet screenshots | `tablet-10/tablet10-1…4.jpg` | 1600×2560 |
| Android TV → TV banner | `tv-banner-1280x720.png` | 1280×720 PNG |
| Android TV → screenshots | `android-tv/tv-1…4.jpg` | 1920×1080 |

Order: Home, Live, Film, Serie. The Android TV section appears in the Console after you opt in to Android TV
(Advanced settings → Form factors).

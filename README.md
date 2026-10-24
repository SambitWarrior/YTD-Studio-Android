# YTD Studio for Android  (v1.1)

Your PC app, ported: same UI in a WebView, with the Python server replaced by a Kotlin
engine around **youtubedl-android** (bundled yt-dlp + ffmpeg).

## Get the APK (no Android Studio needed)
1. Create a new **private** GitHub repo and upload everything in this folder (keep `.github`).
2. **Actions** tab -> *Build APK* -> wait ~5 min.
3. Open the run -> **Artifacts** -> `YTD-Studio-apk` -> unzip.
4. Install **app-arm64-v8a-debug.apk** (almost every phone from the last ~8 years).
   Old 32-bit phones: `app-armeabi-v7a-debug.apk`. Unsure: `app-universal-debug.apk` (bigger).

If the build fails, send me the red error lines.

## What v1.1 adds
- **Auto-fallback**: if YouTube refuses a download, the app updates yt-dlp (at most hourly)
  and retries with up to 3 alternate methods before showing an error.
- **Queue survives restarts**; interrupted jobs show "Press Retry".
- **Playlist retry skips finished items.**
- **Wi-Fi only** mode, storage check, completion/failure notifications.
- New formats: *Most compatible (H.264 MP4)* for TVs/old players, *M4A (no re-encode)*.
- Copy a link, switch to the app: the URL box fills itself. Share from YouTube also works.
- Duplicate links are ignored; "Update yt-dlp now" button in Settings.
- Smaller per-architecture APKs.

## Using it
- Files go to **Downloads/YTD Studio** (playlists get a subfolder).
- Settings -> YouTube sign-in -> cookies.txt -> pick your exported file.
  Cookies are never in the APK; they're copied to the app's private storage.
- Works with any site yt-dlp supports, not just YouTube.

## Limits
- No folder picker or browser-cookie import (Android doesn't allow it).
- Min Android 10.
- Not compile-tested by me: first build may need a small fix. The YouTube fallback client
  names (tv, web_safari, android_vr, mweb) are the most likely thing to need tweaking over time.

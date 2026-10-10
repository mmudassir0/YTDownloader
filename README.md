# YT Downloader (personal Android app)

Downloads YouTube videos and whole playlists straight to your phone's
`Downloads/YTDownloader` folder. Uses the open-source NewPipe extractor on-device,
so there is no server or API key.

## Features
**Get tab**
- Paste a link, **Share → YT Downloader** from the YouTube app, or just type to **search YouTube**
- Videos, playlists and **channels** (newest 10–250 uploads)
- Pick exactly which playlist/channel videos to download (thumbnail checklist, All / None)
- Every format with its size; exact total size for playlists; free space shown before you start
- Up to **4K**: 1080p and below as MP4 (H.264 + AAC); 1440p/4K as WebM (VP9 + Opus, Android 10+)

**Downloads tab**
- One queue for everything; downloads continue with the app closed
- 1–3 downloads at once, **Wi-Fi only** option, **Pause / Resume all**
- Interrupted downloads **resume** where they stopped (even after a restart)
- Per video: **Skip**, **Retry**, Remove, Open with, Share; speed and time left
- A failed or private video never stops the rest of a playlist
- Files you already have are skipped instead of duplicated

**Library tab**
- Built-in player that **continues where you left off**; audio keeps playing in the background
  with lock-screen controls
- **Synced lists**: playlists/channels kept with "Keep for Sync". **Sync** queues only the new
  videos; turn on **Auto-sync** in Settings to do it every night

**Extras (Settings)**
- **Subtitles** saved as `.srt` next to the video (players pick them up automatically)
- **SponsorBlock**: cut sponsor, self-promo, intro and outro segments out of the file
- Audio-only files get title, artist and **cover art**
- **In-app updates**: checks this repo's GitHub Releases and installs new builds

## Build the APK

### Option A — Android Studio (easiest)
1. Install Android Studio (Ladybug or newer).
2. File → Open → select this `YTDownloader` folder. Let Gradle sync (first sync downloads the SDK and libraries).
3. Build → Build App Bundle(s) / APK(s) → **Build APK(s)**.
4. APK: `app/build/outputs/apk/debug/app-debug.apk`.

### Option B — Command line
Requires JDK 17 and the Android SDK (`ANDROID_HOME` set).
```bash
./gradlew assembleRelease        # Windows: gradlew.bat assembleRelease
```
APK: `app/build/outputs/apk/release/app-release.apk` (signed with the committed key, so it installs over earlier builds).

### Option C — GitHub Actions (no PC needed)
Every push builds the APK. Pushes to `main` also publish it as the latest **Release**,
which the app's built-in updater installs. Other branches only check that the app builds
(their APK is under the run's artifacts).

Releases are signed with `app/ytdownloader.keystore` (committed, personal use only) so each
new build installs as an update. `versionCode` = 100 + the CI run number.

## Install on your phone
1. Copy the APK to your phone (USB, Google Drive, WhatsApp to yourself, etc.)
   — or with USB debugging on: `adb install -r app-release.apk`.
2. Tap the APK in your Files app. Android will ask to allow installing from that
   source (Files/Chrome/Drive) — allow it, then tap **Install**.
3. If Play Protect warns about an unknown app, choose **Install anyway**.
4. Open the app and allow notifications so you can see download progress.
5. After that, updates come from inside the app (Settings → Check for updates). The first
   time, Android asks you to allow installs from YT Downloader.

## Notes / limits
- If downloads start failing after a while, YouTube changed something: bump the
  `NewPipeExtractor` version in `app/build.gradle.kts` to the latest release and rebuild.
- For personal use. Downloading may conflict with YouTube's Terms of Service; only download
  content you have the right to keep offline.

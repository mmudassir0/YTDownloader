# YT Downloader (personal Android app)

Downloads YouTube videos and whole playlists straight to your phone's
`Downloads/YTDownloader` folder. Uses the open-source NewPipe extractor on-device,
so there is no server or API key.

## Features
- Paste a link, or **Share → YT Downloader** from the YouTube app
- Single video: pick 1080p, 720p, 480p, 360p or audio-only; the download size and free space are shown before you start
- HD (720p/1080p) is downloaded as separate video + audio and merged on the phone (no quality loss)
- Playlist: "Best video" or "Audio only" for every video, saved into a folder named after the playlist, numbered in order
- Progress shows in the notification shade (Android DownloadManager)

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
APK: `app/build/outputs/apk/release/app-release.apk` (signed with the debug key so it installs directly).

### Option C — GitHub Actions (no setup on your PC)
Push this folder to a **private** GitHub repo. The included workflow builds on every push;
open the repo's **Actions** tab → latest run → download the `YTDownloader-apk` artifact
(it's a zip containing the APK).

## Install on your phone
1. Copy the APK to your phone (USB, Google Drive, WhatsApp to yourself, etc.)
   — or with USB debugging on: `adb install -r app-release.apk`.
2. Tap the APK in your Files app. Android will ask to allow installing from that
   source (Files/Chrome/Drive) — allow it, then tap **Install**.
3. If Play Protect warns about an unknown app, choose **Install anyway**.
4. Open the app and allow notifications so you can see download progress.

## Notes / limits
- If downloads start failing after a while, YouTube changed something: bump the
  `NewPipeExtractor` version in `app/build.gradle.kts` to the latest release and rebuild.
- For personal use. Downloading may conflict with YouTube's Terms of Service; only download
  content you have the right to keep offline.

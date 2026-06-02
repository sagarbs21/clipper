# YouTube Shorts Clipper (Android)

A native Android app (Kotlin + Jetpack Compose) that clips segments from a YouTube
video and exports them in vertical **YouTube Shorts** format (1080x1920, 9:16).
Clip times are fully user-defined.

## How it works

- **NewPipeExtractor** resolves a YouTube link into a playable stream URL — no Google API key.
- **Media3 Transformer** (Google's official, maintained library) trims the segment and
  reformats it to 1080x1920 vertical. No ffmpeg/Python to install.
- Finished clips are saved to **Movies/ShortsClipper** and appear in your gallery.

## Frontend and backend — both are handled, on-device

This is a fully self-contained app. There is **no separate server to run or deploy**:

- **Frontend (UI):** Jetpack Compose — `MainActivity.kt` / `ClipperScreen`.
- **"Backend" (logic), all on the device:**
  - `YoutubeRepository` + `DownloaderImpl` talk directly to YouTube (NewPipeExtractor).
  - `VideoProcessor` does the trimming + 9:16 re-encode (Media3 Transformer).
  - `MediaStoreSaver` writes the finished clip into your gallery.

Everything runs locally on the tablet, so you don't host or maintain anything.

## Optimized for Xiaomi Pad 6

- **Tablet layout:** content is capped at a comfortable reading width and centered, so the
  form doesn't stretch awkwardly across the 11" (2880x1800) screen. Works in portrait and
  landscape, and survives rotation (state lives in the ViewModel).
- **Edge-to-edge** with a top app bar and proper status/navigation-bar insets.
- **Keep-screen-on during export** — also avoids interruptions from MIUI/HyperOS power management.
- **Scoped storage** (MediaStore) — correct for the Pad 6's Android 13/14 (HyperOS); no legacy
  storage permission needed.
- **High refresh rate (144 Hz):** Compose renders at the panel's rate automatically.

> MIUI/HyperOS tip: if exports ever get interrupted, open
> **Settings → Apps → Shorts Clipper → Battery saver → No restrictions**, and lock the app in
> the recents screen while a long export runs.

## Build & install (you do this on a machine with Android Studio)

This project is intentionally not built in your office workspace. To build it:

1. Install **Android Studio** (Hedgehog or newer recommended).
2. **File → Open** and select this `youtube-shorts-clipper-android` folder.
3. Let Gradle sync. It downloads Gradle 8.9, the Android Gradle Plugin 8.5.2,
   and all dependencies (including NewPipeExtractor from JitPack) automatically.
4. Connect your Android phone with **USB debugging** enabled (or use an emulator).
5. Press **Run** (the green ▶) to install and launch on the device.

To produce a shareable APK instead:
**Build → Build Bundle(s) / APK(s) → Build APK(s)** →
`app/build/outputs/apk/debug/app-debug.apk`. Copy it to your phone and install
(enable "Install unknown apps" for your file manager).

> Note: There is no `gradle-wrapper.jar` committed (it is a binary). Android Studio
> supplies Gradle on open. If you build from the command line, run
> `gradle wrapper` once in the project folder to generate the wrapper, then use `./gradlew`.

## Using the app

1. Paste a YouTube URL and tap **Fetch Video**.
2. Tap **+ Add clip** for each segment. Set **Start** and **End**
   (e.g. `90`, `1:30`, or `0:01:30`) and an optional output name.
3. Pick a **crop mode**:
   - **Center crop** – fills 9:16 by cropping the sides (best for most videos)
   - **Fit (bars)** – keeps the whole frame with black bars
   - **Stretch** – stretches to fill (may distort)
4. Tap **Export Clips**. Find results in **Gallery → Movies/ShortsClipper**.

## Tech / versions

| Component | Version |
|---|---|
| Android Gradle Plugin | 8.5.2 |
| Kotlin | 1.9.24 |
| Compile/Target SDK | 34 |
| Min SDK | 24 (Android 7.0) |
| Media3 | 1.3.1 |
| NewPipeExtractor | v0.26.2 (JitPack, group `com.github.teamnewpipe`) |

## Important notes

- **Source quality:** muxed (single-file) YouTube streams typically top out at 720p.
  The output is still encoded at 1080x1920; 720p source is fine for Shorts.
- **NewPipeExtractor may need updating** over time as YouTube changes. If fetching
  stops working, bump the `NewPipeExtractor` version in `app/build.gradle.kts`
  to the latest tag from https://github.com/TeamNewPipe/NewPipeExtractor/releases
  (keep the lowercase group id `com.github.teamnewpipe`).
- **Respect YouTube's Terms of Service and copyright.** Download/clip only content
  you have the rights to use.

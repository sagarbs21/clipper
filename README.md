# Shorts Clipper (Android)

A native Android app (Kotlin + Jetpack Compose) that clips segments from **either a
YouTube link or a video already on your device**, and exports them in vertical
**Shorts** format (1080x1920, 9:16). Clip times are fully user-defined.

## How it works

- **Online:** **NewPipeExtractor** resolves a YouTube link into a playable stream URL — no Google API key.
- **Offline:** the system file picker (Storage Access Framework) lets you select any
  video on the device; its `content://` URI feeds the same pipeline. No storage permission needed.
- **Media3 Transformer** (Google's official, maintained library) trims the segment and
  reformats it to vertical 9:16 (1080x1920 or 720x1280) for both sources. No ffmpeg/Python to install.
- An in-app **preview player** lets you scrub the source and set clip start/end from the playhead.
- Finished clips are saved to **Movies/ShortsClipper** and appear in your gallery.

## Aspect-ratio modes (no forced cropping)

Choose how the source maps into 9:16 — the default keeps the **whole frame**:

| Mode | What it does |
|---|---|
| **Fit · no crop** (default) | Whole frame, scaled to fit, with bars. Nothing is cut. |
| **Blurred fill** | Whole frame on top of a blurred, zoomed copy of itself (Reels/Shorts style). Nothing is cut. |
| **Crop to fill** | Fills the frame by cropping the sides/top. |
| **Stretch** | Stretches to fill (may distort). |

## AI clip suggestions (optional)

Tap **✨ Suggest clips with AI** to have the app detect the **content type**
(movie, TV series, music, sports, gaming, podcast, etc.) and propose trend-style
vertical Shorts with titles and hashtags. Suggestions auto-fill the clip list —
**you review/edit them before exporting**.

- **Choose your AI provider** in Settings — Gemini, **Groq (free, fast, rarely overloaded)**,
  OpenRouter, or OpenAI. If Gemini returns 503 (overloaded), switch to **Groq**.
  Each provider keeps its own key + model (editable). Get a free key:
  - Gemini → [aistudio.google.com/apikey](https://aistudio.google.com/apikey)
  - Groq → [console.groq.com/keys](https://console.groq.com/keys)
  - OpenRouter → [openrouter.ai/keys](https://openrouter.ai/keys)
  - OpenAI → [platform.openai.com/api-keys](https://platform.openai.com/api-keys)

### Providing the API key (pick one)

You can supply the key in any of these ways — the app uses an in-app key if present,
otherwise the one baked in at build time:

1. **In-app (simplest):** paste it into **Settings → Gemini API key**. Saved locally on the
   device; survives restarts. No rebuild needed.
2. **Local build (Android Studio):** add to `local.properties` (already git-ignored):

   ```properties
   GEMINI_API_KEY=AIza...your_key...
   ```

   It's baked into `BuildConfig` so the app works without typing anything.
3. **Cloud build (GitHub Actions):** add a repo secret named **`GEMINI_API_KEY`**
   (Settings → Secrets and variables → Actions → New repository secret). The workflow
   injects it at build time.

> **Security:** never hardcode the key in source or commit it. `local.properties` and
> GitHub secrets keep it out of git. Note that any key baked into an APK can be extracted
> from that APK, so don't share the built APK publicly, and consider restricting the key in
> Google AI Studio. The in-app option keeps the key only on your device.
- For **YouTube** videos the app sends the **captions transcript** (when available) plus
  title/duration so the model can reason about actual moments. For **local files** or
  caption-less videos it works from the title/duration only (less accurate).
- **Privacy:** AI mode sends that text (transcript + title) to Google's Gemini API.
  Manual clipping sends nothing — it's fully on-device. The API key is stored only in
  local app preferences.
- **"Trends":** suggestions reflect the model's knowledge + prompt guidance, **not**
  live trend scraping. Treat them as a smart starting point, not a guarantee.
- **Busy/timeout (HTTP 503):** Gemini's free tier can be momentarily overloaded. The app
  retries automatically with backoff; if it still fails, wait a few seconds and tap again.
- The **model is editable in Settings** per provider (e.g. `gemini-2.5-flash`,
  `llama-3.3-70b-versatile` for Groq). Each provider remembers its own key and model.

## Settings

- **Output quality:** `1080p` (1080x1920) or `720p` (720x1280, smaller files).
- **Gemini API key:** enables AI suggestions (see above).
- The app warns on clips longer than **3 minutes** (YouTube Shorts limit) and does a
  quick **free-space check** before exporting.

## Frontend and backend — both are handled, on-device

This is a fully self-contained app. There is **no separate server to run or deploy**:

- **Frontend (UI):** Jetpack Compose — `MainActivity.kt` / `ClipperScreen`.
- **"Backend" (logic), all on the device:**
  - `YoutubeRepository` + `DownloaderImpl` talk directly to YouTube (NewPipeExtractor).
  - `LocalVideoRepository` reads metadata from a picked device video.
  - `VideoProcessor` does the trimming + 9:16 re-encode (Media3 Transformer) for both sources.
  - `MediaStoreSaver` writes the finished clip into your gallery.
  - `Prefs` stores settings (quality, API key) in local SharedPreferences.
  - **AI (optional):** `CaptionsRepository` fetches a YouTube transcript and `AiClipPlanner`
    calls Google Gemini to classify the content and propose clips.

Everything except the optional AI step runs locally on the tablet; there is still no
server of your own to host or maintain.

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

1. Choose a source:
   - Paste a **YouTube URL** and tap **Fetch Video**, or
   - tap **Choose a video on this device** and pick a local file.
2. Use the **preview** to scrub to a moment, then add clips either way:
   - **Manually:** tap **+ Add clip**, type **Start** / **End** (e.g. `90`, `1:30`, `0:01:30`)
     or tap **Set start/end from preview** to grab the current playhead position.
   - **With AI:** tap **✨ Suggest clips with AI** to auto-fill suggested segments
     (requires a Gemini API key in Settings).
3. Pick an aspect-ratio mode (default **Fit · no crop**). Choose **Blurred fill** for the
   Reels-style look that keeps the whole frame.
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

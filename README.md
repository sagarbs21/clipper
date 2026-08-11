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
| **Blurred fill** | Whole frame stays **sharp** in the middle; only the bars around it are filled with a blurred blow-up of the same frame (Reels/Shorts style). Nothing is cut. |
| **Crop to fill** | Fills the frame by cropping the sides/top. |
| **Stretch** | Stretches to fill (may distort). |

Blurred fill needs API 26+ and the source frame size. The app reads that from the
file (local videos) or probes the stream once (YouTube); if neither works it falls
back to **Fit · no crop** rather than guessing the frame shape.

## AI clip suggestions (optional)

Tap **✨ Suggest clips with AI** to have the app detect the **content type**
(movie, TV series, music, sports, gaming, podcast, etc.) and propose trend-style
vertical Shorts with titles and hashtags. Suggestions auto-fill the clip list —
**you review/edit them before exporting**.

- **Choose your AI provider** in Settings — Gemini, **Groq (free, fast, rarely overloaded)**,
  OpenRouter, or OpenAI. If Gemini returns 503 (overloaded), switch to **Groq**.
  Each provider keeps its own key. Get a free key:
  - Gemini → [aistudio.google.com/apikey](https://aistudio.google.com/apikey)
  - Groq → [console.groq.com/keys](https://console.groq.com/keys)
  - OpenRouter → [openrouter.ai/keys](https://openrouter.ai/keys)
  - OpenAI → [platform.openai.com/api-keys](https://platform.openai.com/api-keys)

### Providing the API key (pick one)

You can supply the key in any of these ways — the app uses an in-app key if present,
otherwise the one baked in at build time:

1. **In-app (simplest):** paste it into **Settings → Gemini API key**. Saved locally on the
   device; survives restarts. No rebuild needed.

   > If saved settings keep vanishing, it's the install rather than the app. CI runners are
   > ephemeral, so each build used to be signed with a fresh random debug key; a changed
   > signature forces an uninstall, and uninstalling wipes app data. The workflow now caches
   > one debug keystore across runs so new APKs install as plain updates. You'll need one
   > last uninstall to move onto the cached key.
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
### Picking a model

Leave **Auto · best free model** on and the app asks the provider what it currently
serves, filters that to free text models it can actually call, and picks the strongest
one. It caches that choice for a day, and if a provider ever answers "no such model" it
re-picks from the live list and retries the same request.

This matters more than it sounds. Providers retire models on a few months' notice, so a
hardcoded id turns into a dead app: Groq shut down `llama-3.3-70b-versatile` on
2026-08-16, and OpenRouter had already dropped every free Llama 3.3 variant. Ranking is
deliberately name-agnostic — generation number, then parameter count, then context window
— so it doesn't need editing every time a lineup changes.

Turn the toggle off to pin a specific model; the dropdown lists what the provider is
serving right now, with the free ones first.

### How a clip actually gets chosen

The model never picks timestamps out of thin air. Two passes feed it first:

1. **On-device analysis.** The app scans the compressed stream in a single pass without
   decoding it, and records how many bytes each second holds. Video bitrate rises with
   motion and scene changes; audio bitrate rises through loud, dense passages, which is
   what a laugh, a goal or a drop looks like. It then scores every 30-second window,
   keeps the busiest non-overlapping ones, and snaps each start onto a real shot change
   (a sync sample) so clips don't open mid-shot.
2. **Captions.** For YouTube videos with subtitles, the timestamped transcript goes in too.

The model receives those candidate windows with their loudness/motion scores, and ranks,
trims and names them. The status line tells you what a given run was based on, so you can
tell a measured suggestion from a guess.

Local files are scanned directly. For YouTube the app scans the **audio-only** track,
which is a few MB rather than the whole video. A 360p muxed fallback is skipped rather
than pulling the entire file down, and in that case suggestions fall back to captions or,
failing that, rough guesses — which the status line says outright.

- **Privacy:** AI mode sends text (transcript, title, and the candidate timings) to your
  chosen provider. The scan itself is entirely on-device, and manual clipping sends
  nothing at all. The API key is stored only in local app preferences.
- **"Trends":** suggestions reflect the model's knowledge + prompt guidance, **not**
  live trend scraping. Treat them as a smart starting point, not a guarantee.
- **Busy/timeout (HTTP 503):** Gemini's free tier can be momentarily overloaded. The app
  retries automatically with backoff; if it still fails, wait a few seconds and tap again.
- The **model is editable in Settings** per provider (e.g. `gemini-2.5-flash`,
  `llama-3.3-70b-versatile` for Groq). Each provider remembers its own key and model.

## Settings

- **Output quality:** `720p`, `1080p`, `1440p` or `4K`, always 9:16, encoded at 6 / 12 /
  24 / 45 Mbps respectively. Higher isn't just a bigger file: a 1920x1080 source fitted
  into a 1080-wide vertical canvas is squeezed to 1080x607, so most of the original detail
  is discarded before encoding. A taller canvas keeps more of it.
- **AI provider + key + model:** enables AI suggestions and metadata (see above).
- **YouTube account:** connect to upload clips (see next section).
- The app warns on clips longer than **3 minutes** (YouTube Shorts limit) and does a
  quick **free-space check** before exporting.

## Channel manager: AI metadata + upload to YouTube

After you export clips, a **"Ready to upload"** list appears. If an AI key is set,
each clip's **title, description (with #Shorts + hashtags), and tags** are written
automatically as soon as it finishes exporting — no extra tap. For each clip you can:

1. Edit anything, or tap **Regenerate title** for a fresh take.
2. **Upload** — the clip goes to your channel with that metadata.

### One-time YouTube setup (you do this in Google Cloud)

The app uses the **OAuth Device Flow**, so it doesn't depend on the app's signing key —
it keeps working with CI-built APKs.

1. Go to [console.cloud.google.com](https://console.cloud.google.com) → create a project.
2. **APIs & Services → Library →** enable **YouTube Data API v3**.
3. **OAuth consent screen:** User type **External**; add **your own Google account** under
   **Test users** (so you can use it without full app verification).
4. **Credentials → Create credentials → OAuth client ID → Application type:
   "TVs and Limited Input devices"**. Copy the **Client ID** and **Client Secret**.
5. In the app: **Settings → YouTube account →** paste Client ID + Secret → **Connect YouTube**.
   The app shows a code; open **google.com/device** on any device, enter it, and approve.

### Important upload limits (not the app's fault — Google's policy)

- **Unverified projects upload videos as PRIVATE.** Until you complete Google's API
  verification/audit, videos are locked to **private** even if you choose public. Default
  visibility here is **private**; make them public from YouTube Studio, or request an audit.
- **Quota:** an upload costs ~1,600 of the default 10,000 units/day → about **6 uploads/day**.
- Uploading other people's videos is infringement — only upload **your own** content.

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
2. Fastest path — **⚡ Auto-clip with AI** (YouTube links): one tap runs
   fetch → AI picks segments → export → writes titles, then drops them in the
   **Ready to upload** list for you to review and upload. (Needs an AI key.)
3. Or do it step by step:
   - Use the **preview** to scrub to a moment.
   - **Manually:** tap **+ Add clip**, type **Start** / **End** (e.g. `90`, `1:30`, `0:01:30`)
     or tap **Set start/end from preview** to grab the current playhead position.
   - **With AI:** tap **✨ Suggest clips with AI** to auto-fill suggested segments.
   - Pick an aspect-ratio mode (default **Fit · no crop**; **Blurred fill** keeps the whole
     frame Reels-style), then tap **Export Clips**.
4. In **Ready to upload**: the AI title, description and tags are already filled in.
   Edit if you like, then **Upload** (connect YouTube in Settings first).
   Clips are also saved to **Gallery → Movies/ShortsClipper**.

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

- **Source quality:** YouTube has retired every muxed (single-file) stream above 360p,
  so the app takes the best **video-only** stream and merges the separate audio track
  back in, both for the preview and the export. If only a muxed stream exists you get
  360p and a warning-free fallback.
- **NewPipeExtractor may need updating** over time as YouTube changes. If fetching
  stops working, bump the `NewPipeExtractor` version in `app/build.gradle.kts`
  to the latest tag from https://github.com/TeamNewPipe/NewPipeExtractor/releases
  (keep the lowercase group id `com.github.teamnewpipe`).
- **Respect YouTube's Terms of Service and copyright.** Download/clip only content
  you have the rights to use.

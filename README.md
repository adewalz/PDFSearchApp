# PDF Keyword Search — Android (Kotlin)

A Kotlin/Android port of the Pydroid 3 Python script. This is a real Android Studio
project — open it, hit Build, and you get an APK.

## How to build the APK

1. Install **Android Studio** (free, from developer.android.com).
2. `File → Open`, pick the `PDFSearchApp` folder (this one).
3. Let Gradle sync (first sync downloads dependencies — needs internet once).
4. `Build → Build Bundle(s) / APK(s) → Build APK(s)`.
5. The APK lands in `app/build/outputs/apk/debug/app-debug.apk` — copy that to
   your phone (or `Run ▶` with a phone plugged in over USB debugging) and install it.

## Building without a working PC (GitHub Actions, free)

This project already includes `.github/workflows/build-apk.yml`, which builds
the APK in the cloud every time you push code. Everything below can be done
from a phone browser.

1. Go to **github.com**, sign in (or create a free account).
2. Create a new repository (any name, e.g. `pdf-search-app`). Don't initialize
   it with a README — you're uploading one already.
3. On the new repo's page, click **"uploading an existing file"**, then drag in
   (or pick) every file/folder from this unzipped `PDFSearchApp` folder —
   including the hidden `.github` folder, `app/`, `build.gradle.kts`, etc.
   - Phone browsers can be finicky about uploading nested folders at once.
     If drag-and-drop of the whole folder doesn't work in your browser, use
     **GitHub Codespaces** instead (see step 3b) — it's a full cloud dev
     environment with a terminal, works great from a phone/tablet browser,
     and `git add . && git commit && git push` handles nested folders fine.
   - 3b (recommended): create the empty repo, then click **Code → Codespaces →
     Create codespace on main**. In the terminal that opens, upload this zip
     via the Codespaces file explorer (right-click → Upload), unzip it
     (`unzip PDFSearchApp.zip`), move its contents into the repo root, then:
     ```
     git add .
     git commit -m "Initial commit"
     git push
     ```
4. Commit the upload. This triggers the **Build APK** workflow automatically.
5. Go to the repo's **Actions** tab, open the latest run, wait for the green
   checkmark (a few minutes).
6. Scroll down to **Artifacts** and download `pdf-search-debug-apk` — that's
   a zip containing `app-debug.apk`. Unzip it on your phone and tap the APK
   to install (you'll need to allow "install from unknown sources" once).

No Android Studio, no PC, no SDK setup required for this path — GitHub's
cloud runners already have everything needed to build an Android APK.

## What this app does (same idea as the Python script)

- Pick a folder of PDFs (via Android's folder picker — no typing raw paths)
- Enter one or more keywords/phrases (comma- or newline-separated)
  - Multi-word phrases match flexibly by default (tolerates PDF line breaks)
  - Prefix a phrase with `=` to force exact-phrase matching, e.g. `=invoice total 2025`
- Scans every PDF directly inside that folder, page by page, using PdfBox-Android
  (a pure-Java/Kotlin PDF text library — no native code, works on all architectures)
- Results stream in live as a scrollable list of expandable cards, each showing
  matched keywords, page numbers, and highlighted snippets
- Tap a snippet to see the full page text with every keyword bolded
- Save / Skip / Reset individual results, like the browser UI's buttons
- Export your results to CSV or TXT (saved under the app's own folder:
  `Android/data/com.pdfsearch.app/files/reports/`)
- **Stop/Resume**: tap Stop mid-scan to pause it (the current file finishes first,
  nothing is lost). Progress is also checkpointed to disk after every single PDF —
  if the app crashes or gets killed by Android mid-scan, relaunching it shows a
  banner offering to resume right where it left off, with everything already found
  still intact.
- **A finished scan's results survive a killed app too.** Previously, only mid-scan
  progress was checkpointed — a *completed* scan's results lived only in memory, so
  if the app was killed before you tapped Export, they were gone. Now the full
  result set is saved the moment a scan finishes, and reopening the app loads it
  back in automatically.
- **Password-protected PDFs** get a "Locked" badge and an Unlock button — enter the
  password and it re-scans just that file, in place.
- **Scanned/image-only PDFs are flagged, not hidden.** A PDF with no extractable
  text layer (a photographed document, for instance) previously looked identical to
  one that genuinely had zero matches. Now it gets a "Possibly scanned" badge and an
  explanatory note, so you know the difference between "not found" and "couldn't be
  searched." (This is detection only, not OCR — actually reading text out of a scan
  would need a much larger on-device OCR engine; flagging it is the practical
  middle ground.)
- **Folder permission loss is caught, not silent.** If Android or the user revokes
  access to the chosen folder, the app says so explicitly instead of just showing
  "0 PDFs found."
- **Open the real PDF** from any result, in whatever PDF viewer you have installed.
- **Share exported CSV/TXT** straight to another app via the Android share sheet,
  instead of a toast with a buried file path.
- **Recursive subfolder scanning** is now an opt-in checkbox.
- **Remembers your last folder, keywords, and settings** across app launches.

## What's different from the Python/Pydroid version (and why)

- **No embedded HTTP server + browser UI.** A native Android app can just show a
  RecyclerView directly — faster, no port 8000, no "which browser do I open."
- **No raw filesystem paths / `/storage/emulated/0/...`.** Modern Android (10+)
  restricts direct path access; the app uses the Storage Access Framework folder
  picker instead, which works on every Android version without special permissions.
- **Runs as a foreground service**, not a coroutine tied to the Activity. The scan
  survives you leaving the app, locking the screen, or Android reclaiming the
  Activity — it only stops when it actually finishes (or you force-stop the app).
  A persistent notification shows live progress and reopens the app on tap.
- **Non-recursive by default**, matching the Python tool's behaviour (only PDFs
  directly inside the chosen folder, not subfolders). Say the word if you want
  recursive subfolder scanning — it's a small change in `PdfSearchEngine.listPdfFiles()`.

## Project layout

```
app/src/main/java/com/pdfsearch/app/
  Models.kt               - data classes (KeywordEntry, PdfResult, PdfStatus, ScanConfig, ...)
  PdfSearchEngine.kt       - regex building, snippet extraction, per-file & folder scan,
                             password retry, no-text-layer detection, recursive listing
  ScanForegroundService.kt - runs the scan in the background; handles start/stop/resume
  ScanRepository.kt        - shared state the service writes to and the Activity observes
  CheckpointManager.kt     - saves/loads MID-SCAN progress to disk (JSON) for crash recovery
  LastScanManager.kt       - saves/loads the FULL results of the last COMPLETED scan
  ResultSerialization.kt   - shared PdfResult <-> JSON helpers used by both of the above
  PrefsManager.kt          - remembers last-used folder/keywords/settings
  PermissionUtils.kt       - checks whether SAF folder access is still valid
  ReportExporter.kt        - CSV/TXT report writers
  ResultsAdapter.kt        - RecyclerView adapter for the expandable result cards
  MainActivity.kt          - folder picker, keyword input, starts/observes the scan service
  PageViewerActivity.kt    - full-page text viewer with highlighting
app/src/main/res/       - layouts, strings, colors, icon
```

## Dependencies used

- `com.tom-roush:pdfbox-android` — PDF text extraction (the Android port of Apache PDFBox)
- AndroidX core/appcompat/material/recyclerview/documentfile
- Kotlin coroutines for background scanning without blocking the UI

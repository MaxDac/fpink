# FP-Ink

Android app that turns a photograph of fountain-pen handwriting into independent,
locally stored paragraph notes with editable text and ink colour.

> **Microsoft Global Hackathon 2026** — internal, non-production, Microsoft IP.
> This project is not for external distribution.
>
> Innovation Studio project page: <INNOVATION_STUDIO_URL>

---

## Screenshots

<!-- Replace with real screenshots after first demo -->
| Capture | Processing | Note Detail |
|---------|-----------|-------------|
| ![Capture](docs/screenshots/capture.png) | ![Processing](docs/screenshots/processing.png) | ![Detail](docs/screenshots/detail.png) |

## Prerequisites

- Android Studio (stable channel)
- JDK 17
- An Android device (minSdk 26); a camera is optional when importing images
- The native runtime and model assets required by `:recognition:paddle`
- Optional: your own Azure **Document Intelligence** resource for online recognition

## Configuration

### Appearance

**Settings > Appearance** offers **System (default)**, **Light**, and **Dark**.
Selection applies throughout the app immediately and is saved independently of
**Save settings** for recognition. System follows device appearance changes;
manual choices ignore them. Changing appearance does not save or discard
unfinished endpoint, API-key, or provider edits. If saving fails, the last saved
appearance is restored and Settings displays a retryable error.

The app uses the standard Material light/dark palettes (no dynamic accent
selection). Photos and note ink colours remain unchanged. Back, Settings, Add
notes, camera, and Delete actions use icons with accessible names and long-press
tooltips; ambiguous actions and confirmation choices keep their text.

The launcher uses an original white fountain-pen nib pointing lower-left from an
upper-right base on indigo, with adaptive and monochrome Android artwork. The
editable master is kept under `docs/design`.

### Recognition

Choose a recognition provider in **Settings**:

| Provider | Configuration | Language scope | Image handling |
|----------|---------------|----------------|----------------|
| PaddleOCR (default) | Bundled PP-OCRv5 mobile detector, recognizer and matching dictionary | English initially | Local CPU inference; no app-managed model download or cloud fallback |
| Azure Document Intelligence Read | Resource HTTPS endpoint and API key | English and Italian | Uploads the selected image to your configured resource |

**Offline PaddleOCR requires ARM64.** Other app dependencies retain their
existing ABIs; on other architectures, Paddle reports unsupported-device rather
than silently selecting Azure.

Azure uses `prebuilt-read`, API `2024-11-30`, without font-style or other paid
analysis add-ons. Its entry paid OCR price is approximately USD 1.50 per 1,000
pages in East US; consult your resource's current region/tier pricing. Paragraph
colour is calculated locally with either provider.

The Azure key is encrypted with an Android Keystore-backed key before storage.
Provider choice and endpoint are stored on-device. Existing Azure OpenAI
credentials are **not** reused: Document Intelligence is a different resource,
and no deployment name is required. The connection check makes a non-image
resource request; it does not upload a journal page or prove OCR accuracy.

Changing providers never silently retries an image with another provider.
Missing models, invalid configuration and recognition errors stop the operation.

## Capture and notes

Use the **Take photo** camera icon, or **Choose image** to select an image through a compatible
gallery/file application. Gallery import does not need camera permission or broad
photo-library access. Android can only offer apps that implement its image-picker
contract; FP-Ink cannot force an incompatible gallery to participate.

Confirm the image and selected provider before processing. The app imports a
private copy, normalizes orientation and encoding, recognizes text, then creates
one note per logical paragraph. Wrapped lines stay together where the provider's
paragraph information or layout permits; ambiguous handwriting/layout may need
manual correction.

Dominant colour means the largest share of **foreground ink pixels**, not the
most words or the average paper colour. If a reliable colour cannot be estimated,
the note uses black and indicates that it was defaulted. Text and colour are
editable independently for every note. Editing or deleting a paragraph does not
change its siblings; their shared source image is retained until the final note
using it is deleted.

## Build & Run

```powershell
# Debug build
.\gradlew.bat :app:assembleDebug

# Run unit tests
.\gradlew.bat :core:model:test :core:ai:test :core:storage:test :app:testDebugUnitTest

# Install to connected device
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Set `ANDROID_HOME` to your SDK installation. The Paddle module owns its native
toolchain, artifact checks and build instructions. Do not replace missing assets
with an empty model or an unrelated OCR checkpoint.

### Current build and device status

The appearance/icon update passes all 31 app JVM tests and 13 focused
instrumentation tests on the Pixel 7 API 37 emulator (appearance, settings, and
capture). Debug build, lint, and the packaged-recognition check pass. UI tests
explicitly use Espresso 3.7 because the older Compose-transitive version calls
an InputManager API removed on newer Android versions. Production dependencies
and SDK levels are unchanged.

Appearance tests cover immediate application, live system configuration,
manual overrides, system-bar contrast, independent encrypted recognition
settings, pending form edits, accessible Back targets, defaults, serialized
rapid selections, recreation, and read/write failures. The app waits for its
saved preference before drawing content; the platform starting window may
still follow the device theme before that preference is available.

### Recorded recognition baseline (2026-09-15)

Debug/release APKs and both instrumentation APKs assemble successfully. The
130 JVM tests, 15 app acceptance tests and six native OCR tests pass. Full lint
has zero errors; remaining warnings are dependency notices and non-blocking
style/icon suggestions.
The debug package check (`:app:verifyDebugRecognitionPackage`, also part of
`:app:check`) verifies that the actual APK contains the models, dictionary,
notice, JNI wrapper, Paddle runtime and C++ runtime. Packaged model hashes and
16 KB ZIP/ELF alignment have also been checked.

| Artifact | Measured size |
|----------|---------------|
| Debug APK | 41,568,917 bytes (41.57 MB) |
| Unsigned release APK | 36,513,057 bytes (36.51 MB) |
| Additional private model/dictionary files after first OCR use | 21,793,695 bytes (21.79 MB) |

Installed app size and runtime memory have **not** been measured. Android's
compiled-code/cache overhead and saved images/notes add to these figures.

Android acceptance ran on a task-owned Android 11 emulator using ARM64 native
translation. Real bundled OCR passed a clean first-launch test with the test
APK's Internet permission removed. A complete synthetic photograph-to-note
case produced exactly two paragraph notes with detected blue/green ink, a shared
local source, and no cloud request. Android decoding, all eight EXIF orientations,
cross-UID URI grants, private-copy survival, Keystore and file-backed note
recovery were also exercised.

A manual offline app run on 2026-09-15 imported the public-domain
[Looped cursive sample](https://commons.wikimedia.org/wiki/File:Looped_cursive_sample.jpg)
through Android's image picker and saved one local note. Against its 42-word
handwritten verse, Paddle produced nine word substitutions (21.4% word error
rate, ignoring case and punctuation). Examples include `snow` -> `smow` and
`Gathering` -> `lyathering`. The measured photo colour was gray (`#BDBDBD`);
the grayscale source cannot establish the original pen ink hue. The displayed
90% model confidence was not a measured word-accuracy score. This single sample
demonstrates a working workflow, but also the need for manual cursive corrections.

This is **not** physical-phone, real fountain-pen cursive, or 16 KB-page-device
qualification: the emulator has 4 KB pages. Camera permission-result handling
and picker contracts are covered, not every OEM gallery or the actual system
permission dialog. These device/quality limits remain documented rather than
being inferred from passing synthetic tests.

For an ARM64-native-bridge emulator, install the target app explicitly with
`adb -s <serial> install -r --abi arm64-v8a <debug-apk>` before native pipeline
tests; otherwise Android may select its x86 AndroidX libraries. App acceptance
uses runner
`com.fpink.capture.test/com.fpink.capture.acceptance.AcceptanceTestRunner`.
The separate Paddle test APK uses
`com.fpink.recognition.paddle.test/androidx.test.runner.AndroidJUnitRunner`.

## Architecture

### Modules

| Module | Purpose | Android imports? |
|--------|---------|:---------------:|
| `:core:model` | Data classes, serialization models | ❌ |
| `:core:ai` | Recognition and note-processing contracts, Azure Read adapter, local paragraph/colour processing | ❌ |
| `:core:storage` | Recoverable paragraph batches and `NoteRepository` over `FileStore` | ❌ |
| `:recognition:paddle` | Bundled native CPU OCR adapter, models and provenance | ✅ |
| `:app` | Compose UI, image import, CameraX, encrypted settings, orchestration and Android storage | ✅ |

**Rule:** `:core:*` modules must contain **zero** `android.*` / `androidx.*` imports.
This keeps them promotable to Kotlin Multiplatform `commonMain` source sets without
a rewrite, enabling a future iOS target.

### Data Flow

```
Camera / image chooser -> private prepared image -> selected RecognitionProvider
    -> NoteProcessor (paragraphs + local colour) -> local paragraph-note batch
```

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the full module diagram, Note
contracts, storage semantics and error handling.

## Phase 0 Scope

**In scope:** camera/gallery intake, selectable photo OCR, local paragraph/ink-colour
processing, protected provider settings, local JSON storage and standalone editable notes.

**Explicit non-goals (Phase 0):**
- No cloud sync
- No Google integration, Entra sign-in or hosted credential broker
- No automatic provider failover or cloud colour analysis
- No stylus/digital-ink input or note categories
- No note linking or tagging (schema fields reserved for Phase 1)
- No iOS target
- No sheen / shimmer detection
- No nib-profile inference

## Roadmap

| Phase | Focus |
|-------|-------|
| **Phase 1** | Zettelkasten scaffolding — note linking, tags, search, Entra auth, Key Vault |
| **Phase 2** | Richer ink metadata, additional recognition providers, iOS via Kotlin Multiplatform |

## Known Limitations

- Notes are local to the device — no sync
- Selected Paddle model support is initially English; offline Italian is not promised
- Fountain-pen cursive accuracy requires representative image evaluation
- No full-text search
- Note listing reads the directory each time (no Room/SQLite index — fine for MVP scale)
- Large inputs are bounded/downsampled; tiny handwriting can lose detail
- Colour is an uncalibrated photo estimate affected by lighting, ruling and paper
- The approximately 4.7 MB detector plus 16 MB recognizer are **not** a final APK
  size: converted assets, dictionary, native runtime and Android dependencies add overhead
- Real Android inference, native page-size compatibility and device UX require
  device acceptance checks; JVM tests alone do not establish them

PaddleOCR's Apache-2.0 components are a potential FLOSS-compatible dependency path,
not approval to publish this application on F-Droid. The internal Microsoft-IP
restriction above is unchanged. Full application licensing, source/build
provenance, transitive dependencies and F-Droid review remain separate gates.

## Security

See [`SECURITY.md`](SECURITY.md). No secrets are stored in source control. API
credentials are protected on-device. Private notes, source images and credentials
are excluded from automatic backup. Azure receives an image only when the user
explicitly chooses the online recognition provider; there is no note-sync service.

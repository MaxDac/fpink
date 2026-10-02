# Bundled PaddleOCR Android adapter

`PaddleOcrProvider(context)` implements the Android-free `RecognitionProvider`
contract in `core:ai`. Its constructor only retains application context.
`readiness(context)` verifies packaged model/dictionary SHA-256 digests and loads
the actual native libraries. **Call readiness off the main thread.**
It does not make a network request or claim to be an OCR accuracy test.

## Validation status and release gate

The pinned public assets have been downloaded and verified. The native geometry,
coordinate, BGR sampling, and CTC tests pass on a Windows host compiler. The full
JNI translation unit compiles against the pinned real Paddle headers. The parent
build also compiled both C++ translation units with the actual NDK r28c.

**Android builds, package checks and translated ARM64 execution pass.**
Debug/release app APKs, both library variants and the native instrumentation APK
build with NDK r28c against the checked-in, source-built Paddle Lite runtime.

The actual app and instrumentation APKs were checked for all three native
libraries, matching model/dictionary SHA-256 hashes, and 16 KB ZIP/ELF alignment.
The runtime must be explicitly included through the module's `native` JNI-library
source directory; successful linking alone does not include it in an APK.
The app's `verifyDebugRecognitionPackage` and `verifyReleaseRecognitionPackage`
tasks guard this requirement for both build types.
The app excludes `libpaddle_light_api_shared.so` from symbol stripping so its
packaged bytes keep the reproducible hash pinned in `source-runtime.lock.json`. The release
pipeline verifies that hash, model/dictionary hashes and bundled license hashes
against the actual APK; app-built JNI and NDK libraries keep normal stripping.

Six native tests passed on an Android 11 x86_64 emulator running the actual ARM64
libraries through `libndk_translation.so`. They cover HELLO/geometry, blank output,
pre/start cancellation, repeated provider use, and repair of partial extracted
models against bundled hashes. The first four also passed after a clean install
with no Internet permission. A separate real app-pipeline case produced two
locally coloured paragraph notes from a generated image.

This emulator has 4,096-byte pages. Physical ARM64, 16 KB-device execution,
cancellation during a known active kernel, extended memory stress and
representative handwriting quality remain unverified. Synthetic and translated results
must not be presented as physical-device performance or cursive-accuracy claims.

A manual production-app run with the public-domain
[Looped cursive sample](https://commons.wikimedia.org/wiki/File:Looped_cursive_sample.jpg)
on 2026-09-15 saved one note with networking disabled. Its 42-word verse had nine
word substitutions after case/punctuation normalization (21.4% word error rate).
The gray colour result (`#BDBDBD`, `DETECTED`) describes the grayscale image, not
the original pen ink. This is one measured cursive example, not a quality
benchmark or evidence that the model's 90% confidence means 90% word accuracy.

## Exact build inputs

* Android library, minSdk **26**, compileSdk **37**, Java/Kotlin bytecode **17**.
  The app also compiles against API 37 for current dependency requirements;
  its targetSdk remains **36**, so this does not opt into new runtime behavior.
* NDK **28.2.13676358** (r28c), CMake **3.22.1**, C++17.
* **Offline PaddleOCR supports only `arm64-v8a`.** No ARM32, x86, or x86_64
  Paddle runtime is included. Other app dependencies retain those ABIs, so this
  is an offline-provider restriction, not a new whole-app installation filter.
  Unsupported devices receive an explicit error without automatic cloud fallback.
* `libpaddle_light_api_shared.so` and the two public headers: **CPU-only**
  Paddle Lite built from the pinned source commit (see
  [Source-built runtime](#source-built-runtime)). Their SHA-256 values are pinned
  in `source-runtime.lock.json` (`build.expectedSha256`). The runtime's ELF LOAD
  segments have 16,384-byte alignment and congruent file/virtual offsets.
* The JNI wrapper links with explicit 16,384-byte page settings.
  `libc++_shared.so` comes from the same NDK, which also compiled the runtime.
  Validate its ELF and the final APK too.
* The runtime statically links the NDK's LLVM OpenMP runtime (libomp); its
  license is bundled as `licenses/LLVM-openmp.txt`.

The parent build includes `:recognition:paddle` and makes the app depend on it.
`preBuild` verifies pinned model, dictionary, license, native-library, and header hashes;
it fails rather than downloading or silently accepting changed binaries.
The AGP Variant API adds `native` to every library variant's JNI sources,
avoiding the legacy source-set API. CI checks both app packages and compiles
the app and library instrumentation APKs; device execution remains separate.

Use `JAVA_HOME` (or your IDE's Gradle JDK setting) to select a local JDK; do not
commit a machine-specific `org.gradle.java.home` path. CI selects JDK 17 and runs
Gradle `build` plus both instrumentation APK assembly tasks on Linux. `build`
already runs JVM unit tests, lint, artifact verification and both APK package
checks, using the checked-in Paddle assets. A separate CI job runs the host C++
unit tests. See [CI setup and required checks](../../docs/CI.md) for commands,
reports and instructions to block merges until both jobs pass. Instrumentation
APKs are compiled, not executed in CI.

```powershell
# From the repository root: verification is entirely local.
& recognition\paddle\scripts\prepare.ps1 -VerifyOnly

# Deliberate developer-only reacquisition of the models, dictionary and licenses,
# if checked-in artifacts are absent. Requires curl.exe and tar; checks archives
# AND individual extracted files. The runtime is rebuilt from source instead.
& recognition\paddle\scripts\prepare.ps1

# Cross-compile/link the real JNI independently of the parent Gradle inclusion:
& recognition\paddle\scripts\validate-native.ps1 -AndroidSdk '<workspace-SDK>'

# After the workspace Android SDK is configured:
.\gradlew.bat :recognition:paddle:assembleDebug :recognition:paddle:assembleDebugAndroidTest
.\gradlew.bat :recognition:paddle:connectedDebugAndroidTest

# Host-native algorithm tests (also run by CI), with an existing clang++/g++:
& recognition\paddle\scripts\test-geometry.ps1 -Cxx '<path-to-clang++.exe>'
```

The host test script also works with PowerShell on Linux: pass `-Cxx g++` or
the path to a compatible compiler. It does not load the Android Paddle runtime.

## Model and dictionary provenance

`models-provenance.lock.json` records where the bundled models and dictionary come
from. `scripts/verify-model-provenance.py` checks it against `artifacts.lock.json`.

* **Models**: `upstream-converted`. PaddlePaddle publishes `PP-OCRv5_mobile_det.nb`
  and `PP-OCRv5_mobile_rec.nb` already converted to Paddle Lite's naive-buffer
  format. FPInk redistributes them unmodified as Apache-2.0 model data. The lock
  pins the distribution archives, the `.nb` bytes, and the corresponding
  Hugging Face source checkpoints (revision plus payload hashes). It does **not**
  claim to regenerate the `.nb` bytes: those checkpoints are PaddlePaddle 3 PIR
  programs (`inference.json`), which the Paddle Lite 2.14 `opt` converter cannot
  read.
* **Dictionary**: `derived-from-upstream`. `ppocr_keys_ocrv5.txt` is exactly the
  `PostProcess.character_dict` list of the pinned recognizer `inference.yml`,
  joined by LF. CI downloads that file, checks its hash, and verifies the
  derivation:

```bash
python3 recognition/paddle/scripts/verify-model-provenance.py \
  --check-files --fetch-dictionary-source   # needs PyYAML
```

No Python, desktop Paddle, model optimizer, model conversion, account,
questionnaire, download service, accelerator SDK, or OpenCV is required at runtime.
No source-format `pdiparams` or duplicate source checkpoints are packaged.

## Source-built runtime

The checked-in runtime and headers are the output of this build. F-Droid and the
signed release rebuild them from source, and CI proves the result byte-identical.
They are built with
`scripts/build-runtime.sh`, which checks out the exact Paddle Lite commit in
`source-runtime.lock.json`, applies the recorded source preparation (removes
the unused Java demo containing a prebuilt `gradle-wrapper.jar`, pins the
unused flatbuffers `ExternalProject` to a GitHub commit, renames a helper that
shadows Python's `ast` module), invokes the upstream tiny-publish
`lite/tools/build_android.sh` with the recorded CPU-only arguments, copies
`inference_lite_lib.android.armv8/cxx/{lib,include}` outputs into the module,
checks the AArch64 ELF and 16 KB alignment, and writes
`build/source-output/SHA256SUMS` and `PROVENANCE`. The tiny-publish build uses
no network after checkout and fails if any `ExternalProject` download occurs.

The script has two phases so the network fetch can run before a source scan
and the compilation after it:

```bash
bash recognition/paddle/scripts/build-runtime.sh fetch     # network: git fetch of the pinned commit
NDK_ROOT=/path/to/ndk/28.2.13676358 \
  bash recognition/paddle/scripts/build-runtime.sh build   # offline compilation
./gradlew -PpaddleRuntimeBuiltFromSource :app:assembleFossRelease
```

Without either property, Gradle checks the checked-in runtime and headers against
`build.expectedSha256`. With `-PpaddleRuntimeBuiltFromSource`, Gradle does not
rebuild anything: it also requires `PROVENANCE` to match
`source-runtime.lock.json` and the three files to match `SHA256SUMS`. `-PbuildPaddleRuntimeFromSource` still runs the whole script
(`all`) from Gradle, which is convenient locally but hides the script output in
the Gradle daemon.

Host tools: `bash`, `git`, `python3`, `cmake` (3.31 on the F-Droid buildserver;
CMake 4 works through `CMAKE_POLICY_VERSION_MINIMUM`), `make`, coreutils
(`sha256sum`, `nproc`), `sed`, `grep`, `awk`, and `readelf` from binutils (the NDK's
`llvm-readelf` is used as a fallback). The Android build itself uses the NDK
clang; no host C++ compiler is needed for the runtime (the JNI geometry host
test still needs `g++`). The NDK is taken from `NDK_ROOT`, `ANDROID_NDK_ROOT`,
`ANDROID_NDK_HOME` or `ANDROID_NDK` and must be revision `28.2.13676358`.
On Debian: `apt-get install -y git python3 cmake make binutils`.

The F-Droid recipe deletes the checked-in runtime before building, so F-Droid
never uses it. Developers and CI use it directly, so they test the shipped bytes.

### Reproducibility of the source-built runtime

Signed GitHub releases and F-Droid both build this runtime from source in the
same buildserver image (see `docs/RELEASING.md`), and the resulting APKs must be
byte-identical. To make the runtime deterministic, `build-runtime.sh`:

- sets `SOURCE_DATE_EPOCH` to the pinned Paddle Lite commit time;
- maps the source, NDK and NDK-symlink paths with `-ffile-prefix-map`;
- links with `--build-id=none`;
- strips the output with the NDK's `llvm-strip --strip-unneeded`. It is
  packaged with `keepDebugSymbols`, so AGP never strips it again.

The `build` phase prints the SHA-256 of the runtime and headers.
`-PpaddleRuntimeBuiltFromSource` fails when a build differs from
`build.expectedSha256`. Use `-PallowUnpinnedPaddleRuntime` only to diagnose such
a failure locally. To change the runtime, update the pins and the checked-in
files together, using `scripts/fdroid-rb-docker.sh` output, and repeat the
Android tests.

## Public provenance (no gated material)

The official [v3.4.1 on-device guide](https://www.paddleocr.ai/v3.4.1/en/version3.x/deployment/on_device_deployment.html)
advertises PP-OCRv5 mobile on CPU. Its shell-demo source package has a questionnaire
step, which was **not** accessed. Instead, the public, Apache-2.0
[Paddle-Lite-Demo source revision](https://github.com/PaddlePaddle/Paddle-Lite-Demo/tree/71c8499765fab203335f2d159ea8d51e0d1914c2)
publishes the model and dictionary download URLs in `ocr/assets/download.sh`.

The selected files are the official, already-converted
`PP-OCRv5_mobile_det.nb`, `PP-OCRv5_mobile_rec.nb`, and
`ppocr_keys_ocrv5.txt` (18,383 dictionary entries; CTC adds blank and space).
The corresponding original model cards declare Apache-2.0 and English capability:

* [Detector source-model revision](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det/tree/0d63e78e2b680928f6b1747d76a08db6e645efb7)
* [Recognizer source-model revision](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec/tree/682f20538d8c086cb2128e5cfac775e6c4904e85)
* [Paddle Lite runtime source revision](https://github.com/PaddlePaddle/Paddle-Lite/tree/28fe23f222de1865e01f0ab41d2494a1222be4f0)

`artifacts.lock.json` records the model and dictionary source URLs, archive
sizes/digests and individual shipped-file sizes/digests. Upstream bucket URLs are
mutable; a changed download is deliberately rejected. No alternate model/engine
is substituted.

The app initially advertises **English**, not offline Italian. This multilingual
checkpoint is not a guarantee of accurate arbitrary English cursive.

## Native behavior and bounds

* The input is the orientation-corrected `PreparedImage.pixels` ARGB buffer,
  not an independently decoded copy of its encoded bytes.
* Detector: BGR bilinear resize of the long side to 960, dimensions rounded to
  multiples of 32; published ImageNet mean/std normalization; real Lite `Run()`.
* DB postprocessing: threshold 0.3, score threshold 0.6, eight-connected components,
  convex hull/minimum rotated rectangle, expansion distance
  `polygonArea * 1.5 / polygonPerimeter`. The custom implementation expands that
  rectangle; it is not a byte-for-byte copy of OpenCV/Clipper's rounded polygon
  offset. This intentional implementation difference needs image-quality testing.
* Coordinates are normalized by the **detector output map** width/height and
  then used to sample the **original prepared pixels**. Detector rounding cannot
  turn resized-input pixel coordinates into incorrect source-image coordinates.
  Regions remain normalized to the original prepared image.
* Recognizer: affine-quad crop, BGR normalization to `[-1, 1]`, height 48, width
  preserving aspect ratio, minimum padded width 320, real Lite `Run()`, greedy
  CTC decoding with blank/repetition handling and the matching v5 dictionary.
  UTF-8 text crosses JNI as bytes, not modified-UTF-8 `NewStringUTF`.
* Tall crops rotate 90 degrees as in Paddle's crop convention. No optional
  orientation classifier is bundled; arbitrary upside-down text is not promised.
* At most 16,000,000 input pixels, 16–8192 pixels per side, 1,000 detector
  candidates, 256 detected lines, and 2,048 recognizer input columns. Exceeding
  limits fails visibly rather than silently truncating the journal.
* Only CTC lines with mean emitted-token confidence at least 0.5 are returned.
  No-text/blank output remains an empty document, never invented text.
* A process-wide coroutine mutex serializes native jobs. Each job uses at most
  two Lite CPU worker threads and owns its predictors through C++ RAII. Detector
  storage is released before allocating recognizer tensors. There are no durable
  exposed handles or abandoned background native jobs.
* Cancellation is checked before model setup, through preprocessing, after each
  kernel and between text lines. A running kernel is not forcibly torn down.
  After it completes, cancellation is thrown and stale results are discarded.
* Verified assets are materialized, with synchronized staging and SHA checks, in
  app-private `noBackupFilesDir/paddle-v5`. They can be regenerated from APK assets
  after interruption. There is no runtime network client, cloud fallback, or
  persistent note/image storage inside the adapter.

## Measured artifact sizes

| Artifact | Actual bytes |
|---|---:|
| Detector NB | 5,001,214 |
| Recognizer NB | 16,718,470 |
| **NB model pair** | **21,719,684** |
| Matching dictionary | 74,011 |
| ARM64 Lite CPU shared library (source build) | 4,992,264 |
| Packaged JNI wrapper, debug / release | 228,088 / 70,016 |
| Packaged NDK libc++ | 1,253,544 |
| Debug app APK | 41,568,917 |
| Unsigned release app APK | 36,513,057 |
| Native instrumentation APK | 31,271,646 |
| Installed app and physical-device runtime memory | **Not measured** |

The model pair is about 20.71 MiB (21.72 decimal MB). The assets plus dictionary
also require **21,793,695 bytes of private model-file storage** after first use,
in addition to their APK representation. The APK figures above measure this build,
not installed size. Compression, ABI splits, app code, wrapper, libc++, and
alignment affect subsequent builds. Runtime tensor memory is separate again.

Host test compiler used: portable `llvm-mingw-20260908-ucrt-x86_64`,
archive SHA-256 `1bcf74d06b724aeecaa6412ca85f5b26fb1da770e7cdcefa9263c9c5c3ad34b6`.
It was installed only in this module's ignored build directory after the native
test failed because `g++` was missing; it is not shipped in the app.

## Required Android checks

1. Reproduce the completed debug/release builds, verify all three packaged OCR
   `.so` files with `scripts\verify-elf.ps1`, and run SDK
   `zipalign -c -P 16 -v 4` on the final APK.
2. On a clean ARM64 test install, run without network access using
   `PaddleNativeTest`. Its generated English image must produce actual `HELLO`
   text and correctly placed geometry; its white page must produce zero lines.
   The instrumentation manifest removes the test framework's `INTERNET`
   permission to enforce offline execution without changing a user's network
   settings. Confirm that permission is absent from the rebuilt test APK.
   If Android's native bridge is used on an x86 emulator, record the result as
   translated ARM64 execution, not physical ARM64 or 16 KB-device validation.
3. Repeat on a 16 KB-page ARM64 device. ELF alignment alone is not this test.
4. Exercise cancellation during native kernels, a second queued job, repeated
   jobs, process interruption during model extraction, and missing/corrupt assets.
   Check no stale output and bounded native memory after repeated runs.
   Repeated HELLO/blank/HELLO calls and partial private-cache repair already pass;
   those do not prove kernel-time cancellation, abrupt process-kill recovery or
   long-run memory stability.
5. Use authorized English handwriting photographs, rotations, small text, and
   multiple line lengths to measure transcription/geometry quality. The single
   cursive sample above is insufficient for qualification; representative
   accuracy and physical-device performance still need measurement.

Licenses/notices are bundled under `src/main/assets/paddle/licenses` and in
`NOTICE.txt`. FPInk's original code is licensed under GPL-3.0-only; see the root
`LICENSE` and `README.md`. Third-party runtime, models, headers and notices retain
their respective licenses. See `docs/RELEASING.md` for the release and F-Droid
source-build process.

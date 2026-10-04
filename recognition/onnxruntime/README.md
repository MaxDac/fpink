# ONNX Runtime built from source

Release builds (`foss` and `full`) run recognition on ONNX Runtime 1.30.0
built from source by `scripts/build-runtime.sh`, with telemetry compiled out
(`onnxruntime_USE_TELEMETRY=OFF`). Debug builds keep the upstream
`com.microsoft.onnxruntime:onnxruntime-android` AAR from Maven Central,
because emulator tests also need its x86_64 libraries. Recognition uses only
the CPU execution provider, so this build leaves out NNAPI and XNNPACK.

Nothing from ONNX Runtime is committed. The module holds only the build
script, the lock file and the Gradle wiring; the script writes everything it
packages into the git-ignored `generated/` directory:

| Path | Content |
|---|---|
| `generated/jniLibs/arm64-v8a/libonnxruntime.so` | ONNX Runtime, built with the pinned NDK and stripped |
| `generated/jniLibs/arm64-v8a/libonnxruntime4j_jni.so` | ONNX Runtime's Java JNI binding, built from `java/src/main/native` |
| `generated/java` | ONNX Runtime's Java API (`java/src/main/java` and `java/src/main/android`), copied unchanged from the pinned checkout |
| `generated/assets/onnxruntime` | ONNX Runtime's `LICENSE` and `ThirdPartyNotices.txt`, copied from the pinned checkout |

Every release APK, whether from the Release workflow, the Reproducibility
workflow or F-Droid, generates them from source with the F-Droid recipe. An
ONNX Runtime update therefore touches only `source-runtime.lock.json` and
`gradle/libs.versions.toml`.

It leaves out the AAR's `java/src/main/android-telemetry` sources: the
`ai.onnxruntime.TelemetryInitializer` provider and the 1DS HTTP client that
asks for `INTERNET`. `:app:verify*ReleaseRecognitionPackage` rejects a release
APK whose dex or native code still mentions `ai/onnxruntime/telemetry` or
`events.data.microsoft.com`.

## Pins

`source-runtime.lock.json` pins:

- the ONNX Runtime commit (tag `v1.30.0`);
- the source subset kept after the checkout;
- the SHA-1 of every CMake dependency archive (the same ones that
  `cmake/deps.txt` pins);
- the NDK revision and CMake options;
- `build.expectedSha256`, the SHA-256 of every shipped file. For `generated/java`
  the entry is a digest over `<sha256>  <path>` lines sorted by path.

`:recognition:onnxruntime:verifyOrtRuntime` runs before every build of the
module and checks whatever is present in `generated/` against
`build.expectedSha256`. Only release variants use the module (debug uses the
Maven AAR), so debug builds, unit tests and lint work without `generated/`.
A release build without it fails (`requireSourceBuiltOrtRuntime`).

## Rebuilding

The script runs in two phases, mirroring F-Droid's `prebuild` and `build`.

1. `build-runtime.sh fetch` needs network access. It:
   - fetches the pinned commit into `build/source/onnxruntime`;
   - deletes everything outside `preparation.keep`, including tests, test
     models, other bindings and the Gradle wrapper;
   - downloads the dependency archives into
     `${FPINK_ORT_DEPS_DIR:-~/.cache/fpink/onnxruntime-deps}`, outside the
     scanned tree.
2. `NDK_ROOT=<ndk 28.2.13676358> build-runtime.sh build` works offline. It:
   - builds a host `protoc` from the pinned protobuf and Abseil archives, with
     the patches ONNX Runtime applies, instead of downloading a prebuilt
     `protoc`;
   - builds ONNX Runtime against the mirror, and fails if CMake fetched
     anything outside it;
   - builds the JNI library with the NDK's clang;
   - strips both libraries and checks that they are AArch64 ELF files, 16 KB
     aligned, link only system libraries, and contain no telemetry strings;
   - writes `build/source-output/{PROVENANCE,SHA256SUMS}`.

A full `build` takes about 13 minutes on a 16-thread machine, about 3 of them
for the host `protoc`. The stripped `libonnxruntime.so` is about 19 MB.

Host tools: bash, git, python3, curl, CMake 3.28 or newer, Ninja, patch, a host
C++ compiler and a JDK (`javac`, for the JNI headers).

The output depends on the NDK, the ONNX Runtime sources and
`SOURCE_DATE_EPOCH`, which the script sets to the ONNX Runtime commit time.
Build paths are mapped away, so a build in F-Droid's `buildserver-trixie` image
at `/home/vagrant/build/com.fpink.capture` matched a local WSL build in another
directory byte for byte. The pinned hashes come from the Reproducibility
workflow. To update them, copy `onnxruntime/SHA256SUMS` from its `rb-*`
artifacts into `build.expectedSha256`.

To build a release APK locally, run the F-Droid replay
(`scripts/fdroid-rb-docker.sh`), which runs the script and Gradle in F-Droid's
buildserver image. Running the script directly on Linux or WSL also works, but
only the buildserver image's toolchain matches the pinned hashes.

Gradle properties:

- `-PortRuntimeBuiltFromSource`: the script has just rebuilt the files, so
  also check `PROVENANCE`/`SHA256SUMS` against the lock. The F-Droid recipe
  sets it.
- `-PallowUnpinnedOrtRuntime`: accept a local rebuild whose hashes differ
  from `build.expectedSha256`. Use it for local experiments only.

The F-Droid recipe (`metadata/com.fpink.capture.yml`) runs the script itself:

- `prebuild: build-runtime.sh fetch`;
- `build: build-runtime.sh build`;
- `gradleprops: ortRuntimeBuiltFromSource`.

F-Droid's build only succeeds when its rebuild matches the pinned bytes.

# F-Droid metadata and build validation

This procedure validates the eventual `metadata/com.fpink.capture.yml` in a
clean Linux environment. It does not create or submit fdroiddata changes.

## Prerequisites and ordering

Run this procedure only after:

1. **#27** has replaced the bundled Paddle Lite runtime with a source-buildable
   recipe, or F-Droid maintainers have explicitly accepted the documented
   dependency path.
2. **#28** has resolved model, dictionary, license, and conversion provenance.
3. **#31** has produced finalized metadata for an immutable release commit.
4. **#30** has supplied the upstream Fastlane listing metadata and changelog
   required by the release being validated.

The build must use a public HTTPS repository and a full immutable commit SHA.
Do not use a local checkout, private fork, SSH credentials, `file:` dependency,
unpushed patch, unpinned branch, release workflow value, GitHub secret,
keystore, signing certificate, or cached application output.

## Clean environment

Use a fresh Debian-based host or an F-Droid buildserver VM. Record:

- Linux distribution/image and architecture.
- `fdroidserver` version or commit, Python version, Git version, and whether
  the isolated buildserver VM was used.
- JDK 17 or 21 (Gradle toolchains are not used; Kotlin/Java target 17).
- Android platform 37 and build-tools 36.0.0.
- NDK `28.2.13676358`.
- CMake 3.22.1 (app JNI) and CMake 3.31 (Paddle Lite source build), plus a
  GCC-compatible C++17 compiler.
- Paddle Lite source-build host tools: `bash`, `git`, `python3`, `cmake`,
  `make`, coreutils, `sed`, `grep`, `awk`, `binutils` (`readelf`).
- Gradle wrapper version and distribution checksum.

### Paddle Lite runtime in the recipe

Build the runtime in two phases so the network fetch happens in `prebuild`
and compilation happens in `build`, after the F-Droid source scan (a `.so`
produced in `prebuild` would be flagged by the scanner):

```yaml
    sudo:
      - apt-get update
      - apt-get install -y git python3 cmake make binutils
    rm:
      - recognition/paddle/native/arm64-v8a/libpaddle_light_api_shared.so
    prebuild: bash -x recognition/paddle/scripts/build-runtime.sh fetch
    build: NDK_ROOT=$$NDK$$ bash -x recognition/paddle/scripts/build-runtime.sh build
    ndk: r28c
    gradleprops:
      - paddleRuntimeBuiltFromSource
```

`paddleRuntimeBuiltFromSource` makes Gradle verify
`recognition/paddle/build/source-output/PROVENANCE` and `SHA256SUMS` against
`recognition/paddle/source-runtime.lock.json` instead of the prebuilt hashes;
it does not rebuild the runtime.

Acquire only public sources declared by the metadata and provenance records.
Record every URL, revision, archive hash, SDK package, and tool version. After
acquisition, repeat the build with network access disabled where the buildserver
allows it. No credentials or signing secrets may be present in the environment.

## Metadata checks

From the fdroiddata checkout containing
`metadata/com.fpink.capture.yml`, save command output, error output, and exit
status for every command:

```bash
fdroid readmeta
fdroid readmeta com.fpink.capture
```

Confirm that the filename and application ID are `com.fpink.capture`, the
repository is public, every build uses a full commit SHA, and the build block
contains literal `versionName` and `versionCode` values.

Canonicalize the metadata and inspect the semantic diff:

```bash
mkdir -p artifacts
cp metadata/com.fpink.capture.yml artifacts/com.fpink.capture.before.yml
fdroid rewritemeta com.fpink.capture
cp metadata/com.fpink.capture.yml artifacts/com.fpink.capture.after.yml
diff -u artifacts/com.fpink.capture.before.yml \
  artifacts/com.fpink.capture.after.yml
```

Formatting, quoting, and key-order changes are expected. Any change to a
source URL, commit, version, Gradle task or property, output, scanner rule, or
dependency is a blocker until reviewed.

Run lint and scanning against the canonical metadata:

```bash
fdroid lint com.fpink.capture
fdroid scan com.fpink.capture
```

Fix all mandatory lint findings. Classify every remaining warning as fixed,
explicitly accepted by an F-Droid maintainer, or a blocker. Do not use
`scandelete`, `scanignore`, or an opaque-binary exception to hide the native
runtime, models, licenses, or dependencies. Scanner exclusions are allowed only
for generated/test material that is demonstrably irrelevant to the release APK.

## Isolated build

Use the F-Droid buildserver for the acceptance run:

```bash
fdroid build --server --resetserver --verbose com.fpink.capture
```

If the buildserver is unavailable, a fresh Debian build without `--server` is
useful for diagnosis but is not equivalent acceptance evidence. Do not reuse the
server snapshot after changing the recipe or source commit.

The recipe must invoke the root Gradle project and provide:

```text
-PrequireReleaseVersion=true
-PreleaseVersionName=<metadata versionName>
-PreleaseVersionCode=<metadata versionCode>
```

It must use the checked-in Gradle wrapper and the finalized public native/model
preparation recipe. It must not fall back to `version.properties` for a release
version or download unpinned artifacts.

## APK acceptance checks

Confirm that fdroidserver finds exactly the configured output APK and retain:
the APK SHA-256, byte size, `aapt2 dump badging` output, and a complete ZIP
entry/hash report. The APK must satisfy all of the following:

- Application ID is `com.fpink.capture`.
- `versionName` and `versionCode` exactly match the metadata and Gradle
  properties.
- The package is a release APK and is not debuggable or a test APK.
- It contains the ARM64 native libraries required by the finalized runtime,
  and no `lib/<abi>/` directory other than `lib/arm64-v8a/`
  (`:app:verifyFossReleaseRecognitionPackage` enforces this).
- It requests no `android.permission.INTERNET` or
  `android.permission.ACCESS_NETWORK_STATE` and does not register
  `ai.onnxruntime.TelemetryInitializer` (`:app:verifyFossReleaseOfflineManifest`,
  part of `check`, enforces this on the merged manifest). `CAMERA` and
  AndroidX's signature-level `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` remain.
- Its dex carries an R8 marker (`~~R8{...}`), and the packaged
  `lib/arm64-v8a/libpaddle_light_api_shared.so` is byte-identical to the
  verified runtime (debug symbols are kept for it on purpose).
- It contains both OCR models, the dictionary, notices, and license texts
  required by the finalized provenance record.
- Native and ZIP alignment checks pass, including the project's 16 KB checks.
- No runtime download is needed after installation.

When the finalized recipe remains compatible with the repository verifier, run:

```bash
python3 scripts/verify_release_apk.py \
  --apk <fdroidserver-output-apk> \
  --version-name <metadata-version-name> \
  --version-code <metadata-version-code> \
  --build-tools "$ANDROID_HOME/build-tools/36.0.0"
```

If the runtime/model implementation changes under #27 or #28, port the same
identity, asset, ELF, and alignment assertions to the replacement verifier
before accepting the F-Droid build.

## Instrumenting the minified release APK

The acceptance suite normally runs against the debug APK. To run it against the
R8-minified `fossRelease` APK on an ARM64 device or an x86_64 emulator with
ARM64 native-bridge translation, use the local-only `instrumentReleaseBuild`
property (it cannot be combined with release version properties):

```bash
./gradlew -PinstrumentReleaseBuild :app:connectedFossReleaseAndroidTest
```

It sets `testBuildType = "release"`, signs with the debug key, adds Compose's
test activity manifest, and applies `app/proguard-release-instrumentation.pro`,
which keeps the classes the test APK calls directly. Because those extra keeps
mask shrinking of FPInk's own classes, also smoke-test the exact
`:app:assembleFossRelease` output (re-signed only with `apksigner`): launch it,
confirm Settings lists both PaddleOCR and Kraken OCR as ready, and recognize an
imported page with each provider. On x86_64 emulators with ARM64 native-bridge
translation, the arm64 ONNX Runtime crashes in its static initializers, so
Kraken detects the translation layer and fails closed as an unsupported device
(its acceptance test is skipped there); validate Kraken on real ARM64 hardware.
Paddle works under translation.

## Repeatability and diagnostics

Delete build outputs, temporary acquisition files, Gradle caches, and the
buildserver snapshot. Recreate the clean environment and repeat the exact
commit and commands. Compare metadata, source/dependency inventories, APK
identity, package entries, native/model hashes, and logs. Byte-identical APKs
are useful evidence but are not required for ordinary inclusion unless the
project chooses shared-signature reproducibility.

Use these blocker paths:

| Failure | Route |
| --- | --- |
| YAML, unsupported field, or semantic rewrite | Fix metadata in **#31** |
| Listing, license, category, or link lint issue | Fix metadata or coordinate with **#30** |
| Native runtime, model, dictionary, license, or scanner finding | Resolve in **#27/#28**; never suppress it |
| Missing SDK, NDK, CMake, JDK, or compiler | Add a public, justified prerequisite to the recipe |
| Gradle version/property or wrong APK output | Fix the build block in **#31** |
| Private path, credential, signing material, or hidden network request | Stop, remove the hidden input, and rerun clean |
| Reproducible buildserver/VM failure | Capture host and guest logs and classify it as infrastructure |

## Evidence bundle

Retain a redacted review bundle containing:

- Metadata commit and full source SHA.
- Prerequisite issue states and maintainer decisions.
- Environment/tool manifest and public input URL/hash inventory.
- `readmeta`, `rewritemeta` diff, `lint`, `scan`, and build logs.
- APK hash, size, badging output, ZIP entries, native/model hashes, and
  alignment results.
- Network-egress summary and repeat-build comparison.

Do not commit transient logs, usernames, private paths, tokens, credentials,
keystores, or signing material. The validation is complete only when all
commands succeed, the rewrite has no unexplained semantic change, mandatory
lint/scanner findings are resolved, the isolated build succeeds, APK identity
and required assets match, and any repeat-build difference is explained.

## References

- [F-Droid submission guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/)
- [F-Droid build metadata reference](https://f-droid.org/docs/Build_Metadata_Reference/)
- [F-Droid inclusion policy](https://f-droid.org/docs/Inclusion_Policy/)
- [F-Droid build server setup](https://f-droid.org/docs/Build_Server_Setup/)
- [`docs/RELEASING.md`](RELEASING.md)

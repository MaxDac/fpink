# Releases and F-Droid readiness

FPInk releases are **manual only**. CI still runs on pushes and pull requests,
but it never publishes a release. The Release workflow has no push, tag, schedule
or pull-request trigger.

## One-time owner setup

1. Review the `GPL-3.0-only` licensing choice in the root `README.md` and `LICENSE`.
   This replaces the previous FPInk non-distribution notice; third-party licenses
   remain unchanged. Confirm that you have the rights to distribute the code,
   bundled models, native runtime and artwork. Adding a license does not by itself
   prove rights to third-party material or F-Droid eligibility.
2. Create a GitHub environment named **release**, restrict deployment branches to
   `main`, and enable required reviewers where your GitHub plan supports them.
   The workflow fails unless this environment supplies the variable
   `RELEASE_PUBLICATION_APPROVED` with the exact value `true`.
3. Arrange a persistent Android release keystore. Back up the keystore, alias and
   passwords securely outside GitHub before publishing. Never use the debug key,
   generate a new key per release, or commit the keystore.
4. Set these **environment secrets**, not workflow inputs:

   | Secret | Value |
   |---|---|
   | `RELEASE_KEYSTORE_BASE64` | Single-line base64 encoding of the existing keystore |
   | `RELEASE_KEYSTORE_PASSWORD` | Keystore password |
   | `RELEASE_KEY_ALIAS` | Private-key entry alias |
   | `RELEASE_KEY_PASSWORD` | Private-key entry password |

5. Set the environment variable `RELEASE_CERTIFICATE_SHA256` to the signing
   certificate's SHA-256 fingerprint, obtained independently with `keytool`.
   Upper/lowercase and colon-separated fingerprints are accepted. The workflow
   verifies the APK against it and against previous release manifests. Key
   rotation is deliberately unsupported until a migration strategy is designed.
6. Merge the workflow into `main`. GitHub only exposes manual dispatch when the
   workflow exists on the repository's default branch.

No signing key, environment configuration or public release is provisioned by
the source-code change alone. Treat environment configuration as an owner
approval, not something a routine CI run should create.

## Run a release

In **Actions > Release > Run workflow**, choose `main`.

- Leave **version** blank for the next minor release. The first release is
  `0.1.0`; after `0.4.2`, the automatic version is `0.5.0`.
- Enter a stable `X.Y.Z` value to select a different patch/minor/major release.
  It must be newer than the latest stable release and at least the source
  baseline. Leading zeros, prerelease suffixes and build metadata are rejected.
- Leave **publish** unchecked for a build-only rehearsal. It produces an
  **unsigned, non-installable APK artifact**, uses no signing secrets, and creates
  no tag or release. This is the default.
- Check **publish** to build, obtain environment approval, sign and publish.
  Review the resolved version and source SHA in the build summary before
  approving. GitHub's native form cannot dynamically prefill the next version.

Equivalent commands:

```text
gh workflow run release.yml --ref main -f version=0.1.0 -F publish=false
gh workflow run release.yml --ref main -f version=0.1.0 -F publish=true
```

The release build is tied to the dispatch commit, not to a moving branch.
It must contain the latest released source. A concurrency group prevents active
release runs from overlapping; GitHub may replace an older *pending* run when
multiple dispatches queue, so confirm that the intended run actually started.

## Versioning and output

`version.properties` holds local-build defaults. Release name/code overrides
are supplied together through Gradle properties; the workflow requires them
explicitly. The Android code is one greater than the maximum baseline/published
code, starting at `2`, with a maximum of `2100000000`. It is not a workflow run
number and does not reset for a new major version.

Every published release must contain a valid `release-manifest.json`. Discovery
is paginated and versions are compared numerically. Missing manifests, invalid
history, changed tag targets, inconsistent certificates or an existing target
tag/draft fail closed. Do not manually publish releases outside this scheme.

The GitHub Release contains:

| File | Purpose |
|---|---|
| `FPInk-X.Y.Z.apk` | Signed release APK |
| `release-manifest.json` | Schema version, application ID, version name/code, source SHA, tag, APK name/hash and certificate SHA-256 |
| `SHA256SUMS` | SHA-256 checksums for the APK, manifest and license |
| `LICENSE` | GPL version 3 text for FPInk's original code |

Notes link to the corresponding source at the exact release tag and show the
Gradle properties needed to rebuild it. Preserve the source/tag and third-party
notices when redistributing. Offline PaddleOCR currently supports ARM64 only;
the workflow does not change the app's existing ABI installation behavior.

Only the signing/publishing job has repository write permission. It does not
check out or execute repository build scripts. It receives the current run's
unsigned artifact, restores secrets into a temporary directory, aligns before
signing, verifies the signer and APK identity, then removes the keystore even
on ordinary signing failures. Hosted runners are ephemeral.

Publication creates the tag atomically, creates a draft, uploads every asset,
downloads them again to compare hashes, and only then publishes. Existing tags
and releases are never overwritten.

## Build and validate locally

Use JDK **17** (the release workflow pins Temurin **17.0.18+8**), Android SDK
platform **36**, build tools **36.0.0**, NDK **28.2.13676358**, CMake **3.22.1**,
Python 3 and PowerShell 7 (`pwsh`, also available on GitHub's Ubuntu runners).
The checked-in wrapper pins Gradle.

On Windows, configure `ANDROID_HOME` to your SDK and quote the `-P` arguments:

```powershell
python -m unittest discover -s scripts\tests -v
.\gradlew.bat --no-daemon test :app:lintRelease :app:assembleRelease :app:verifyReleaseRecognitionPackage `
    '-PrequireReleaseVersion=true' '-PreleaseVersionName=0.1.0' '-PreleaseVersionCode=2'
python scripts\verify_release_apk.py `
    --apk app\build\outputs\apk\release\app-release-unsigned.apk `
    --version-name 0.1.0 --version-code 2 `
    --build-tools "$env:ANDROID_HOME\build-tools\36.0.0"
```

On Linux use `./gradlew`, POSIX path separators and `python3`. No release secrets
are required for an unsigned build. The package checks cover the actual release
APK, pinned model/runtime/license bytes, required libraries, application
ID/version, non-debuggable state and 16 KB ELF/ZIP alignment.

Physical ARM64 execution, 16 KB-page device execution, install/upgrade retention,
camera/lifecycle behavior and representative handwriting quality need separate
device qualification. Alignment checks and a successful CI run are not claims
that those checks have passed. See the recognition module's documented limits.

## Failure and recovery

- **Before tagging:** fix the error and dispatch again. A build-only run does
  not consume a version code.
- **Existing tag or draft:** inspect the previous run, tag commit, manifest,
  certificate and uploaded bytes. The workflow intentionally stops rather than
  guessing how to resume. An owner can finish a verified complete draft, or
  explicitly remove an unpublished failed draft/tag before a fresh dispatch.
  Do not remove or move a published version.
- **After publication:** verify the release is public and all assets are
  downloadable. Use a new version for corrections; do not replace an installed
  release's APK or reuse its version code.
- **Lost signing key:** restore the secured backup. A newly generated key does
  not provide a normal in-place update for existing installations.
- **Missing historical manifest:** investigate and reconstruct it from the
  actual APK/tag/signature with owner review. Never substitute code `1`, infer
  a certificate, or silently ignore the release.

## Remaining F-Droid work

The official F-Droid repository builds from reviewed source; publishing a
GitHub APK does not submit or deploy the app there.

| Area | Remaining work |
|---|---|
| Rights and dependency audit | Confirm GPL compatibility and redistribution rights for transitive dependencies, artwork, dictionaries and model weights; preserve their licenses and notices. |
| Paddle native runtime | Replace the bundled prebuilt `.so` with a pinned-source Linux build recipe acceptable to F-Droid, or obtain explicit maintainer approval of an acceptable dependency path. Hash pinning and the documented ELF metadata correction are not source builds. Do not bypass this with scanner exclusions. |
| Model provenance | Supply original weights/source, licensing, conversion steps and tool versions, or obtain maintainer agreement on asset treatment for the shipped `.nb` files. |
| Linux/F-Droid recipe | Validate all preparation and native/model builds in a clean supported build environment without private credentials, local paths or unpublished inputs. Pin permitted downloads and tools. |
| Version discovery | Initially put literal version name/code and Gradle overrides in the F-Droid recipe. Tag auto-updates need regex-readable source metadata plus `UpdateCheckData`; F-Droid does not execute Gradle to discover computed versions. Workflow-only values are not automatically discoverable. |
| Listing | Add Fastlane-compatible descriptions, icon, screenshots, version-code-named changelogs, author/contact information, categories, source/issue links and applicable anti-feature declarations. |
| Optional Azure service | Describe image transfer and user-provided credentials. Discuss `NonFreeNet` with maintainers. Offline is already the default; an offline-only flavor is an option, not an automatic requirement. |
| Identity and signing | Confirm the long-term `com.fpink.capture` identity. Choose F-Droid signing or upstream-signed reproducible builds before first distribution. Do not share the private key. Different certificates normally prevent switching channels in place. |
| Shared-signature reproducibility | If sharing the GitHub signer, demonstrate independent byte-identical APK rebuilding, including native code, then configure `Binaries` and `AllowedAPKSigningKeys`. Ordinary F-Droid inclusion does not require this, but still requires acceptable source builds. |
| Submission | Prepare `metadata/com.fpink.capture.yml` in `fdroiddata`, lint/build it, submit a merge request, resolve review, and maintain update checks. Acceptance and timing belong to F-Droid. |

A self-hosted F-Droid repository is a different distribution route. It requires
hosting, signed indexes and maintenance and does not remove licensing duties.

References: [inclusion policy](https://f-droid.org/docs/Inclusion_Policy/),
[submission guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/),
[anti-features](https://f-droid.org/docs/Anti-Features/),
[reproducible builds](https://f-droid.org/docs/Reproducible_Builds/).

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
3. Arrange a persistent Android release keystore (PKCS12 `.p12` is recommended;
   existing JKS stores also work). Use the same store/key password for PKCS12.
   Back up the keystore, alias and
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
   Both this setting and `RELEASE_PUBLICATION_APPROVED` must be **environment
   variables**, not secrets; the four signing entries above remain secrets.
6. Merge the workflow into `main`. GitHub only exposes manual dispatch when the
   workflow exists on the repository's default branch.

No signing key, environment configuration or public release is provisioned by
the source-code change alone. Treat environment configuration as an owner
approval, not something a routine CI run should create.

## Run a release

First declare the release in a release-bump PR. F-Droid's auto-update reads the
version from `version.properties` at the release tag, so the tagged commit must
already contain it:

```text
git switch -c release/next origin/main
python3 scripts/release_version.py --repository MaxDac/fpink --release-type prerelease --prepare
# edit fastlane/metadata/android/en-US/changelogs/<versionCode>.txt (max 500 characters)
git commit -am "Release <versionName>" && gh pr create --fill
```

`--prepare` computes the next version exactly as the workflow does (pass
`--requested-version` or `--release-type stable` as needed), writes it into
`version.properties` and creates an empty changelog. CI rejects a declared
release whose changelog is missing, empty or too long. After merging, in
**Actions > Release > Run workflow**, choose `main`. The workflow refuses to run
unless the version it computes equals the declared one, so a stale
`version.properties` fails before building; it never commits to `main`.

- Select **release_type**: `prerelease` (the default) or `stable`. Pre-releases
  are marked non-production-ready on GitHub and never replace the Latest release.
- Leave **version** blank for automatic selection. A stable release starts at
  `0.1.0`, then uses the next minor (after `0.4.2`, `0.5.0`), or the highest
  pending preview's base version if that is newer.
- An automatic pre-release uses that same base with `-preview.1`, or advances
  the highest existing prerelease suffix for the base (`preview.9` becomes
  `preview.10`; `rc.1` becomes `rc.2`; `beta` becomes `beta.1`).
- An explicit version must match the selected type: `X.Y.Z` for stable or a
  SemVer prerelease such as `X.Y.Z-preview.2` / `X.Y.Z-rc.1`. It must be newer
  than all published versions, with a base at least the source baseline. Numeric
  identifiers with leading zeros and `+build` metadata are rejected.
- Leave **publish** unchecked for a build-only rehearsal. It produces an
  **unsigned, non-installable APK artifact**, uses no signing secrets, and creates
  no tag or release. This is the default.
- Check **publish** to build, obtain environment approval, sign and publish.
  Review the resolved version and source SHA in the build summary before
  approving. GitHub's native form cannot dynamically prefill the next version.

Equivalent commands:

```text
gh workflow run release.yml --ref main -f release_type=prerelease -F publish=false
gh workflow run release.yml --ref main -f release_type=prerelease -f version=0.1.0-preview.2 -F publish=true
gh workflow run release.yml --ref main -f release_type=stable -f version=0.1.0 -F publish=true
```

The release build is tied to the dispatch commit, not to a moving branch.
It must contain the latest released source. A concurrency group prevents active
release runs from overlapping; GitHub may replace an older *pending* run when
multiple dispatches queue, so confirm that the intended run actually started.

While the fdroiddata merge request is unmerged, the `fdroid-mr` job pushes the new tag to it
automatically, once configured. See
[FDROID.md](FDROID.md#while-the-merge-request-is-open).

## Versioning and output

`version.properties` declares the release built from each commit; release
builds use it directly, with no Gradle overrides, so F-Droid and local builds of
a tag produce the same version. `-PreleaseVersionName`/`-PreleaseVersionCode`
remain available for local experiments only. The Android code is one greater than the maximum baseline/published
code across both stable and pre-release APKs, starting at `2`, with a maximum of
`2100000000`. It is not a workflow run number and does not reset for a new major
version or when a preview becomes stable. The signing certificate must also
remain the same across both release types.

Every published release must contain a valid `release-manifest.json`. Discovery
is paginated and versions are compared numerically. Missing manifests, invalid
history, changed tag targets, inconsistent certificates or an existing target
tag/draft fail closed. Do not manually publish APK releases outside this scheme.

### Existing source-only previews

A source-only preview must be explicitly recorded with this alternate manifest
shape, have GitHub's pre-release flag set, and have no other uploaded assets:

```json
{
  "schemaVersion": 1,
  "applicationId": "com.fpink.capture",
  "versionName": "0.1.0-preview.1",
  "tag": "v0.1.0-preview.1",
  "sourceSha": "fc2d3973579e1a7a254b70a05538d4bbee5b9aa6",
  "sourceOnly": true
}
```

This records the initial source-only preview, not an APK version or a signing
certificate. Its tag is verified against GitHub, the local tag and source
ancestry, and remains reserved. With only this preview published, the next
automatic pre-release is `0.1.0-preview.2` with Android code `2`; the first stable
version is `0.1.0`. Do not overwrite the existing preview/tag.

GitHub's automatic source archives are not uploaded assets. An empty asset list
alone is **not** evidence of a source-only release: missing manifests still fail
so a damaged APK release cannot silently reset version/signing history. Only
backfill this metadata after confirming that no APK was ever distributed for
that tag; never use it to bypass a missing APK manifest.

### APK assets

The GitHub Release contains:

| File | Purpose |
|---|---|
| `FPInk-<version>.apk` | Signed release APK, including any prerelease suffix |
| `release-manifest.json` | Schema version, application ID, version name/code, source SHA, tag, APK name/hash and certificate SHA-256 |
| `SHA256SUMS` | SHA-256 checksums for the APK, manifest and license |
| `LICENSE` | GPL version 3 text for FPInk's original code |

Notes link to the corresponding source at the exact release tag. The APK is a
reproducible build (see below): `scripts/fdroid-rb-docker.sh` at the tag rebuilds
the unsigned APK byte for byte. The asset name `FPInk-<version>.apk` is part of
the fdroiddata `Binaries` URL; do not rename it. Preserve the source/tag and third-party
notices when redistributing. Offline PaddleOCR currently supports ARM64 only;
the workflow does not change the app's existing ABI installation behavior.

Only the signing/publishing job has repository write permission. It does not
check out or execute repository build scripts. It receives the current run's
unsigned artifact, restores secrets into a temporary directory, checks the
existing 16 KB/4-byte alignment, signs the APK in place with
`apksigner sign --alignment-preserved` (v2 and v3 signatures, no v1 or v4),
verifies the signer and APK identity, requires `apksigcopier compare` to confirm
that the signed APK is exactly the unsigned build plus a signature, then removes
the keystore even on ordinary signing failures. Re-aligning or re-padding the
APK would break F-Droid's reproducibility check. Hosted runners are ephemeral.

Publication creates the tag atomically, creates a draft, uploads every asset,
downloads them again to compare hashes, and only then publishes. Existing tags
and releases are never overwritten.

## Reproducible release builds

The release build job runs `scripts/fdroid-rb-docker.sh`, which replays the last
build block of `metadata/com.fpink.capture.yml` with `scripts/fdroid_rb_build.py`
inside the digest-pinned `registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie`
image, like `fdroid build --on-server`: the same apt packages, OpenJDK 21,
gradlew-fdroid, NDK installation, `/home/vagrant/build/com.fpink.capture` path,
`SOURCE_DATE_EPOCH`, `rm`, `prebuild`, `build` and Gradle invocation. The Paddle
runtime is therefore built from source on both sides. On a Linux Docker host:

```text
bash scripts/fdroid-rb-docker.sh out        # builds HEAD (committed files only)
bash scripts/fdroid-server-build.sh out-fd  # the real fdroid build, for comparison
cmp out/unsigned.apk out-fd/unsigned.apk
```

The **Reproducibility** workflow runs both builders (twice for the replay) on
pull requests that touch build inputs and fails unless the APKs are identical;
it attaches diffoscope reports otherwise. See
[F-Droid validation](FDROID_VALIDATION.md#reproducible-builds) for debugging.

## Build and validate locally

Use JDK **17** or **21** (releases use the buildserver's OpenJDK 21), Android SDK
platform **37**, build tools **36.0.0**, NDK **28.2.13676358**, CMake **3.22.1**,
Python 3 and PowerShell 7 (`pwsh`, also available on GitHub's Ubuntu runners).
The SDK manager package for this platform is `platforms;android-37.0`.
The checked-in wrapper pins Gradle.

On Windows, configure `ANDROID_HOME` to your SDK and quote the `-P` arguments:

```powershell
python -m unittest discover -s scripts\tests -v
.\gradlew.bat --no-daemon :app:testFossReleaseUnitTest :app:lintFossRelease :app:assembleFossRelease :app:verifyFossReleaseRecognitionPackage `
    '-PrequireReleaseVersion=true' '-PreleaseVersionName=0.1.0-preview.2' '-PreleaseVersionCode=2'
python scripts\verify_release_apk.py `
    --apk app\build\outputs\apk\foss\release\app-foss-release-unsigned.apk `
    --version-name 0.1.0-preview.2 --version-code 2 `
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

The gated submission and recurring maintenance procedure is documented in
[F-Droid submission and maintenance](FDROID.md). Do not start that procedure
until issues #27, #28, #30, #31, and #32 are complete.

| Area | Remaining work |
|---|---|
| Rights and dependency audit | Confirm GPL compatibility and redistribution rights for transitive dependencies, artwork, dictionaries and model weights; preserve their licenses and notices. |
| Paddle native runtime | Build the pinned source runtime with `-PbuildPaddleRuntimeFromSource` and the recipe in `recognition/paddle/source-runtime.lock.json`; obtain maintainer approval of the exact source/toolchain path. The checked-in `.so` is only a developer fallback during qualification. Hash pinning and the documented ELF metadata correction are not source builds. Do not bypass this with scanner exclusions. |
| Model provenance | Supply original weights/source, licensing, conversion steps and tool versions, or obtain maintainer agreement on asset treatment for the shipped `.nb` files. |
| Linux/F-Droid recipe | Validate all preparation and native/model builds in a clean supported build environment without private credentials, local paths or unpublished inputs. Pin permitted downloads and tools. |
| Version discovery | Done from 0.1.0-preview.8: releases are declared in `version.properties`, which the recipe reads with `UpdateCheckMode: Tags` and `UpdateCheckData`, and the version-agnostic build block lets `AutoUpdateMode: Version` copy it. |
| Listing | Descriptions, icon, phone screenshots and version-code-named changelogs now live in `fastlane/metadata/android/en-US/`. Still needed: author/contact information, categories, source/issue links and applicable anti-feature declarations. Every future release must add its own `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (max 500 characters) alongside the version bump. |
| Optional cloud OCR provider | Public releases (`foss` flavor) are offline-only (bundled PaddleOCR only) and never contact a cloud service; there is nothing to disclose. A private, non-F-Droid "full" build variant, built from a separate private companion repo, may add optional cloud/proprietary recognition providers for the maintainer's own signed releases — see `docs/ARCHITECTURE.md`'s flavor-split section — but that variant is never published to F-Droid. |
| Identity and signing | Confirm the long-term `com.fpink.capture` identity. Choose F-Droid signing or upstream-signed reproducible builds before first distribution. Do not share the private key. Different certificates normally prevent switching channels in place. |
| Shared-signature reproducibility | Releases from 0.1.0-preview.8 are built like the buildserver and checked by the Reproducibility workflow; the recipe sets `Binaries` and `AllowedAPKSigningKeys`. The source-built runtime is pinned in `build.expectedSha256` of `recognition/paddle/source-runtime.lock.json`; update it only together with the Paddle Lite source or toolchain. |
| Submission | Prepare `metadata/com.fpink.capture.yml` in `fdroiddata`, lint/build it, submit a merge request, resolve review, push every new release to the open MR (`scripts/fdroid_mr_bump.py`), and maintain update checks. Acceptance and timing belong to F-Droid. |

A self-hosted F-Droid repository is a different distribution route. It requires
hosting, signed indexes and maintenance and does not remove licensing duties.

References: [inclusion policy](https://f-droid.org/docs/Inclusion_Policy/),
[submission guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/),
[anti-features](https://f-droid.org/docs/Anti-Features/),
[reproducible builds](https://f-droid.org/docs/Reproducible_Builds/).

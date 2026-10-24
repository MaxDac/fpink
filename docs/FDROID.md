# F-Droid submission and maintenance

This runbook covers submitting FPInk to the official F-Droid repository and
maintaining it after acceptance. It does not replace F-Droid review or the
technical prerequisites tracked in issues #27, #28, #30, #31, and #32.

## Submission gates

All five gates are closed and the merge request was merged on 2026-10-15
([fdroiddata!50122](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/50122)).
Keep their evidence current:

| Gate | Required evidence |
|---|---|
| #27 | The OCR runtime has an F-Droid-acceptable source-build path or an explicit maintainer-approved dependency path. Release builds use ONNX Runtime 1.30.0 built from source with telemetry compiled out (`recognition/onnxruntime`); nothing from ONNX Runtime is committed: the F-Droid recipe and the Release workflow build the native libraries and copy the Java API and notices from the pinned source, and the build fails unless they match the pinned hashes. Debug builds keep the Maven Central AAR. |
| #28 | Model sources, licenses, redistribution rights, and regeneration limits are recorded in `recognition/models/artifacts.lock.json` and `recognition/models/README.md`. |
| #30 | `fastlane/metadata/android/en-US/` contains truthful listing text, rights-cleared graphics, and version-code-named changelogs. |
| #31 | `metadata/com.fpink.capture.yml` is finalized against an immutable installable release without guessed scanner exemptions. |
| #32 | Clean Linux `fdroid readmeta`, `fdroid rewritemeta`, `fdroid lint`, and `fdroid build` validation succeeds. |

If a runtime, model, listing, metadata, or build change invalidates a gate,
stop and return to its owning issue before continuing.

## Prepare the submission

1. Fork `https://gitlab.com/fdroid/fdroiddata` and clone the fork. Add the
   official repository as `upstream`, fetch it, and branch from its current
   default branch.
2. Use a focused branch name such as `com.fpink.capture`. Record the
   fdroiddata base commit, fdroidserver version, FPInk release tag, and full
   source commit SHA.
3. Add only `metadata/com.fpink.capture.yml` and other fdroiddata files that
   are required by the finalized recipe. Do not copy upstream Fastlane
   descriptions, screenshots, or generated APKs into fdroiddata.
4. Start from the recipe mirrored in this repository,
   [`metadata/com.fpink.capture.yml`](../metadata/com.fpink.capture.yml). Use
   the full immutable source SHA in `Builds.commit` (the mirror shows the tag
   name for readability). The build block is version-agnostic: `subdir: app`
   with no `output` (fdroidserver finds `app/build/outputs/apk/foss/release/`),
   `prebuild`/`build` paths relative to `app/`, and no version properties,
   because the tagged `version.properties` already declares the release.

5. Keep credentials, signing keys, local paths, opaque binaries, and release
   workflow-only values out of the recipe. Never add scanner suppressions to
   hide runtime or model findings. Any `scandelete` must be justified by the
   clean-build evidence in #32.
6. Updates are automatic from 0.1.0-preview.8: `UpdateCheckMode: Tags` with a
   `^v` release-tag pattern, `UpdateCheckData` reading `versionCode` and
   `versionName` from `version.properties`, and `AutoUpdateMode: Version`.
   Only switch an existing recipe to `Tags` once a tag declaring its release in
   `version.properties` exists; older tags declare `0.1.0`/`1`.
7. Releases from 0.1.0-preview.8 are reproducible and published with the
   upstream signature: `Binaries` points at the GitHub release asset and
   `AllowedAPKSigningKeys` holds the certificate SHA-256
   `2058d113771276175856ad9c6d3f24e4f95f54500995d5171a61e73333ab6155`.

Run `fdroid rewritemeta com.fpink.capture`, inspect the resulting diff, and
create one focused commit, for example:

```text
New app: FPInk
```

## Validate and open the merge request

On the exact fork commit, rerun the clean Linux checks from #32:

```text
fdroid readmeta
fdroid rewritemeta com.fpink.capture
fdroid lint com.fpink.capture
fdroid build com.fpink.capture
```

Confirm that the build uses only public pinned inputs, produces
`com.fpink.capture` with the expected literal version name and code, includes
the required ARM64/native/model artifacts, and needs no credentials or
workstation-specific state. Confirm the recipe points at an installable
release, not a source-only preview.

Open a merge request from the fork branch to the official fdroiddata default
branch with a focused title such as **New app: FPInk**, using the description
template below.

Do not claim acceptance, reproducibility, broader ABI support, or privacy
properties that the recorded evidence does not demonstrate.

### MR description

This section is kept for reference and for any later fdroiddata MR.
Reviewers read the MR description, not the Fastlane text. Keep it current: update
it with every release pushed to the MR, and whenever the build steps, OCR runtime,
models, update check or reproducible-build setup change. Write the part below and
keep F-Droid's `## Checklist` under it, ticking each item that applies. The
checklist items with FPInk-specific notes are:

- srclibs: "No srclibs are used; the ONNX Runtime source is fetched at a pinned
  commit in `prebuild`, explained above."
- ABI split: "Not needed: the only native code is arm64-v8a."
- Auto update and reproducible builds: both ticked. If either is ever disabled,
  untick it and explain why above the checklist.

Replace the `<...>` placeholders. Update the runtime and model notes from
`recognition/onnxruntime/README.md`, `recognition/onnxruntime/source-runtime.lock.json`
and `recognition/models/README.md` at the tag.

```markdown
## New app: FPInk

FPInk (`com.fpink.capture`) is a handwriting capture app. It recognizes handwritten notes entirely on-device and never uses a network service.

* Upstream: https://github.com/MaxDac/fpink (GPL-3.0-only). I am the upstream author.
* Release: [`v<versionName>`](https://github.com/MaxDac/fpink/releases/tag/v<versionName>), commit `<full SHA>`, versionCode <versionCode>.
* Fastlane metadata (en-US title, summary, description, icon, screenshots, changelogs) lives upstream in `fastlane/metadata/android/en-US/`.

### Build notes

* The build uses only the `foss` Gradle flavor. The `full` flavor needs a private companion repository and is never built here.
* OCR runs on ONNX Runtime <ORT version>, built from source. The prebuilt Maven AAR is used only by debug builds.
  * `prebuild` fetches ONNX Runtime at pinned commit `<ORT commit>` (tag `v<ORT version>`, MIT). It deletes the parts the build doesn't use, including tests and the telemetry sources. Every CMake dependency archive is checked against the hashes pinned in `cmake/deps.txt` and `source-runtime.lock.json`.
  * `build` compiles offline after the scanner runs, with NDK <release> (`ndk: <revision>`). It builds a host `protoc` from the pinned sources instead of downloading one. It fails if CMake fetches anything outside the pinned mirror.
  * Options: `onnxruntime_USE_TELEMETRY=OFF`, CPU execution provider only, `onnxruntime_USE_KLEIDIAI=ON` (Arm KleidiAI, Apache-2.0, fetched as source through the same mirror).
  * The script checks that `libonnxruntime.so` and the JNI library contain no telemetry strings. Gradle, with `-PortRuntimeBuiltFromSource`, refuses the build unless both libraries, the Java API and the notices match the hashes pinned upstream.
  * The release APK has no `TelemetryInitializer` provider and requests only `CAMERA`.
  * Details: [`recognition/onnxruntime/README.md`](https://github.com/MaxDac/fpink/blob/v<versionName>/recognition/onnxruntime/README.md).
* Native code is **arm64-v8a only**. The APK is about <size> MB.

### Assets to review

The APK bundles three ONNX models, all Apache-2.0: PP-OCRv6 small detection and medium recognition (with its dictionary), and the Kraken PP-OCRv6-medium recognizer (with its alphabet). They are downloaded from pinned Hugging Face and Zenodo revisions and pinned by size and SHA-256 in `recognition/models/artifacts.lock.json`. Provenance is in [`recognition/models/README.md`](https://github.com/MaxDac/fpink/blob/v<versionName>/recognition/models/README.md). The source scanner did not flag them. Please tell me if they need different treatment.

### Auto update and reproducible builds

* **Auto update:** `UpdateCheckMode: Tags` + `UpdateCheckData` read `versionCode` and `versionName` from `version.properties`, and `AutoUpdateMode: Version`. Every release tag declares its version there, and the build block has no version-specific values.
* **Reproducible builds:** enabled. `Binaries` points at the signed GitHub release APK, and `AllowedAPKSigningKeys` holds its certificate. The release CI builds in `fdroidserver:buildserver-trixie` at F-Droid's build path, so the APK reproduces.

### Validation

* `fdroid lint` and `fdroid rewritemeta` produce no changes.
* All jobs in the fork pipeline pass: <pipeline URL>. `fdroid build` ([job](<job URL>)) reports "compared built binary to supplied reference binary successfully".
```

## Reviewer-response loop

Treat every maintainer comment and CI failure as a required evidence change.

- For metadata-only changes, edit the fork branch, rerun `rewritemeta` and
  `lint`, inspect the diff, and reply with the new commit and results.
- For runtime, model, or build changes, update the owning FPInk issue and
  source first. Create a new immutable release tag when the source changes,
  rerun clean Linux validation, and update the merge request to the new SHA and
  literal version/code.
- Reply point by point with commands, logs, policy references, and links.
  Never bypass scanner findings with a broad suppression.
- Before accepting the merge, confirm the diff remains focused and all five
  submission gates are still closed.

## New releases (since the merge)

[fdroiddata!50122](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/50122)
was merged on 2026-10-15 at `v0.1.0-preview.13` (fdroiddata commit
`ab5e68df4d076960bc784bd7109cddb0f311223b`). From then on, F-Droid's
checkupdates bot owns version updates. Publishing a release with the Release
workflow is all FPInk does: the bot finds the new tag (`UpdateCheckMode: Tags`),
reads `version.properties` at that tag, copies the last build block
(`AutoUpdateMode: Version`) and builds it. Nothing is pushed to fdroiddata.

- Updates show up in the [package page](https://f-droid.org/packages/com.fpink.capture/)
  after the bot's next run and the following build and index cycle, usually a
  few days. Follow them in the
  [fdroiddata history for the recipe](https://gitlab.com/fdroid/fdroiddata/-/commits/master/metadata/com.fpink.capture.yml)
  and the [build status](https://monitor.f-droid.org/builds).
- Because the bot copies the last build block, a release that needs different
  build steps (`sudo`, `prebuild`, `build`, `ndk`, `rm`, ...) also needs an
  fdroiddata MR against `master`. Make the change in the in-repo mirror
  `metadata/com.fpink.capture.yml` first, publish the release, then open an MR
  that updates the recipe to that tag. Write that MR's recipe with
  `scripts/fdroid_mr_bump.py --tag v<versionName> --commit-style sha --metadata <fdroiddata>/metadata/com.fpink.capture.yml`
  on a branch of an up-to-date fork, then run `fdroid rewritemeta`,
  `fdroid lint` and `fdroid build com.fpink.capture:<versionCode>`.
- Keep the mirror pointing at the latest release, so it stays a valid recipe:
  `python3 scripts/fdroid_mr_bump.py --tag latest` in a PR. `--tag latest`
  selects the release tag with the highest `versionCode` that matches
  `UpdateCheckMode` and declares itself in `version.properties`.

Before the merge, the Release workflow's `fdroid-mr` job pushed every release to
the MR's fork branch. It was removed after the merge. Delete the
`FDROIDDATA_DEPLOY_KEY` repository secret and the write deploy key on
`MaxDac/fdroiddata` if they still exist.

## Donation link updates

The upstream donation page is <https://ko-fi.com/maxdac>, also verified in
[`README.md`](../README.md) and [`.github/FUNDING.yml`](../.github/FUNDING.yml).
Keep its `Donate` field in `metadata/com.fpink.capture.yml` synchronized with
the official fdroiddata recipe. Changing this repository's mirror alone does
not update F-Droid's listing, and checkupdates discovers release versions, not
changes to donation fields.
Use the scalar `Donate: https://ko-fi.com/maxdac` for compatibility with
fdroidserver 2.4.5, which does not accept a URL list in this field.

Submit a separate, metadata-only merge request against current fdroiddata
`master` to add or change `Donate`, preserving the live build history and commit
hashes. In a clean Linux fdroidserver environment, run `fdroid readmeta`,
`fdroid rewritemeta com.fpink.capture` and `fdroid lint com.fpink.capture`, then
inspect the diff. Link the upstream README or funding file as ownership
verification required by the [inclusion policy](https://f-droid.org/docs/Inclusion_Policy/).

The optional Settings entry opens the page only after a tap, in an external
browser. It adds no payment SDK, network permission, account requirement or
paid feature. The website's privacy/payment behavior belongs to Ko-fi, not
FPInk. Publish app changes through the normal immutable release and subsequent
F-Droid build/index cycle; neither an upstream change nor validation guarantees
F-Droid reviewer approval or an anti-feature classification.

## After merge

Record the merged fdroiddata commit and monitor the official build and index
cycle. Check the build logs, scanner output, generated APK metadata, repository
entry, and client-visible page for:

- package ID, version name, and version code;
- ARM64 support and native/model contents;
- Fastlane description, icon, screenshots, and changelog;
- licenses, notices, and anti-feature labels; and
- source/tag correspondence.

If the build fails, classify the failure as recipe, environment, source,
provenance, or policy. Fix the smallest responsible layer and link the
concrete log in the owning issue. Never replace a published tag or APK; use a
new immutable upstream release when source changes are required.

## Recurring release maintenance

For every installable release:

1. Build from `main`, run the release tests and APK verification, and create
   exactly one immutable `v<versionName>` tag at the published source commit.
2. Assign an Android `versionCode` greater than every prior stable and
   prerelease code. Never reset it across version lines or when promoting a
   prerelease to stable.
3. Add
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` before the
   release is F-Droid-eligible. Keep it truthful and within the 500-character
   limit.
4. Declare the release in `version.properties` with a release-bump PR
   (`scripts/release_version.py --prepare`, see [RELEASING.md](RELEASING.md)),
   then run the Release workflow. F-Droid's checkupdates bot discovers the new
   tag, reads its `version.properties`, copies the last build block and builds
   it; the reproducible build lets F-Droid publish the signed GitHub APK.
5. If the bot's build does not match, inspect the fdroiddata build log and the
   diffoscope output, fix the source, and publish a new release; never replace
   the GitHub asset.

Never move a release tag, reuse a version code, replace an installed release,
or let a computed GitHub-only version become the only source of F-Droid
version information.

## References

- [F-Droid submission quick start](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/)
- [Build metadata reference](https://f-droid.org/docs/Build_Metadata_Reference/)
- [Inclusion policy](https://f-droid.org/docs/Inclusion_Policy/)
- [Descriptions, graphics, and screenshots](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/)

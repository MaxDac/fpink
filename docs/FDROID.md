# F-Droid submission and maintenance

This runbook covers submitting FPInk to the official F-Droid repository and
maintaining it after acceptance. It does not replace F-Droid review or the
technical prerequisites tracked in issues #27, #28, #30, #31, and #32.

## Submission gates

Do not fork `fdroiddata` or open a merge request until all five gates are
closed and their evidence is still current:

| Gate | Required evidence |
|---|---|
| #27 | The OCR runtime has an F-Droid-acceptable source-build path or an explicit maintainer-approved dependency path. |
| #28 | Model sources, conversion steps, licenses, redistribution rights, and reproducibility limits are documented and accepted. |
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
branch with a focused title such as **New app: FPInk**. Include:

- upstream repository, release tag, full source SHA, version name, and version
  code;
- the validation commands, tool versions, and results;
- links to gates #27, #28, #30, #31, and #32;
- runtime and model provenance decisions and their limitations;
- ARM64-only and offline behavior (the APK packages `lib/arm64-v8a/` only, the
  public `foss` build requests no `INTERNET` or `ACCESS_NETWORK_STATE`
  permission, and ONNX Runtime's bundled 1DS telemetry initializer is removed
  from the merged manifest);
- that release builds are shrunk with R8 (`proguard-android-optimize.txt` plus
  `app/proguard-rules.pro`) while `libpaddle_light_api_shared.so` keeps its
  exact verified bytes;
- the optional cloud-recognition-provider network behavior (present only in privately
  built, non-public variants) and any applicable `NonFreeNet` discussion;
- the F-Droid signing versus shared-signature reproducibility decision; and
- the update-check configuration and the reproducibility evidence (the
  Reproducibility workflow run for the release commit).

Do not claim acceptance, reproducibility, broader ABI support, or privacy
properties that the recorded evidence does not demonstrate.

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

## While the merge request is open

Reviewers may leave an approved MR in the test queue for a long time, and
checkupdates only runs for merged apps. Until the merge, every new installable
release must be pushed to the MR by hand, so reviewers test the current version:

1. Publish the release as usual (see [Recurring release maintenance](#recurring-release-maintenance)).
2. Update both recipes from the new tag. The script reads `version.properties`
   at the tag and rejects a mismatched tag, an empty changelog, or a versionCode
   that does not supersede the current one. It replaces the single build block
   instead of appending one, so the MR stays one version:

   ```text
   git fetch --tags origin
   python3 scripts/fdroid_mr_bump.py --tag v<versionName>
   python3 scripts/fdroid_mr_bump.py --tag v<versionName> --commit-style sha \
     --metadata ../fdroiddata/metadata/com.fpink.capture.yml
   ```

   The mirror here keeps the tag as `commit`, and the fdroiddata fork uses the
   full SHA.
3. In the fork, run `fdroid rewritemeta com.fpink.capture`, `fdroid lint
   com.fpink.capture` and `fdroid build com.fpink.capture:<versionCode>`, then
   push the branch. Commit the mirror change here in a PR.
4. Comment on the MR with the `MR note` line the script prints (tag, SHA,
   versionName, versionCode) and the validation results.

If you want to help the queue move, you can test other waiting MRs and post the
results. This is optional.

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

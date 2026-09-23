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
4. Use the full immutable source SHA in `Builds.commit`, literal
   `versionName` and monotonically increasing `versionCode`, and the actual
   Gradle task and paired release properties:

   ```text
   -PrequireReleaseVersion=true
   -PreleaseVersionName=<versionName>
   -PreleaseVersionCode=<versionCode>
   ```

5. Keep credentials, signing keys, local paths, opaque binaries, and release
   workflow-only values out of the recipe. Never add scanner suppressions to
   hide runtime or model findings. Any `scandelete` must be justified by the
   clean-build evidence in #32.
6. Start with literal versions and manual/static update handling. F-Droid
   cannot run Gradle to discover values supplied through properties. Add
   `Tags`, `AutoUpdateMode`, or `UpdateCheckData` only after regex-based
   discovery has been proven against immutable release tags.

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
- ARM64-only and offline behavior;
- the optional Azure network behavior and any applicable `NonFreeNet`
  discussion;
- the F-Droid signing versus shared-signature reproducibility decision; and
- the initial manual update posture, if automatic discovery is not proven.

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
4. Update fdroiddata with the exact tag SHA and literal version/code. Run
   `fdroid checkupdates`, inspect its diff, then run rewrite, lint, and a clean
   build before merging the update.
5. Enable automatic tag/version discovery only after repeated checks prove that
   release metadata is readable by F-Droid's regex-based updater without
   executing Gradle.

Never move a release tag, reuse a version code, replace an installed release,
or let a computed GitHub-only version become the only source of F-Droid
version information.

## References

- [F-Droid submission quick start](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/)
- [Build metadata reference](https://f-droid.org/docs/Build_Metadata_Reference/)
- [Inclusion policy](https://f-droid.org/docs/Inclusion_Policy/)
- [Descriptions, graphics, and screenshots](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/)

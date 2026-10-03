# Continuous integration

The shared [CI workflow](../.github/workflows/ci.yml) runs on pull requests
targeting `main`, every push to `main`, manual dispatches, and merge-queue
`checks_requested` events targeting `main`. It does not deploy or publish releases.

## Required check and coverage

Configure the **job name**, not the workflow title `CI`, as the required check:

| Required check | Coverage |
|---|---|
| `Build and JVM unit tests` | Release-tooling unit tests, the debug production build, configured JVM unit tests (including the recognition runtime, models and strategies modules), SHA-256 verification of the bundled models, the recognition APK package and offline-manifest checks, and compilation of both instrumentation APKs |

The job runs on Ubuntu 24.04 with JDK 17, the checked-in Gradle wrapper and
Android API 36. No NDK or CMake is needed: debug builds use the prebuilt
ONNX Runtime Android AAR from Maven Central, and release unit tests use the
checked-in source-built ONNX Runtime (`recognition/onnxruntime`), verified
against its pinned hashes. AGP selects any additional SDK Build
Tools it requires.

The workflow uses one Gradle invocation with `--continue`, so independent tasks
can finish after a failure without turning that failure into success. It builds
only the public `foss` product flavor (never `full`, which is reserved for the
maintainer's private companion repo — see `docs/ARCHITECTURE.md`) and runs
`verifyFossDebugRecognitionPackage` and `verifyFossDebugOfflineManifest`.

No emulator, device, connected test, or E2E suite is executed. Compiling
instrumentation APKs does **not** mean those tests passed. Host checks do not
prove real OCR quality, camera/chooser behavior, Keystore behavior, Android
filesystem durability, ARM64 runtime execution, or 16 KB-device compatibility.
Those checks remain separate; see the
[model validation notes](../recognition/models/README.md).

The workflow uses read-only repository permissions and does not need
credentials for any recognition service, signing secrets, deployment
environments, or self-hosted runners.
Gradle cache writes are limited to push/manual runs on `main`; PRs only read
caches. New commits cancel older runs of the same PR. Main pushes, manual runs,
and merge-group runs have separate concurrency groups and do not cancel each
other. The required job skips neither drafts nor documentation-only PRs.

## Reports

Open **Actions > CI > a run** and inspect the job's logs. The job uploads a
`verification-reports` artifact after success or failure containing the reports
that were produced:

- JVM HTML reports under each module's `build/reports/tests/`.
- JUnit XML under each module's `build/test-results/`.
- Android lint reports under each module's `build/reports/lint-results*`.

Reports expire after 14 days. Early setup/compilation failures may produce no
reports; the upload warns and the original job failure is preserved. No APKs
are published.

## Reproduce locally

Set `JAVA_HOME` to a JDK 17 installation and `ANDROID_HOME` to your Android SDK,
with the platform version above installed and SDK licenses accepted. Do not
commit machine-specific paths. From the repository root on Windows:

```powershell
py -m unittest discover -s scripts/tests
.\gradlew.bat --continue --console=plain --stacktrace :app:assembleFossDebug :app:testFossDebugUnitTest :core:ai:test :core:model:test :core:storage:test :recognition:runtime:test :recognition:models:test :recognition:strategies:test :app:assembleFossDebugAndroidTest :recognition:strategies:assembleDebugAndroidTest :app:verifyFossDebugRecognitionPackage :app:verifyFossDebugOfflineManifest
```

On Linux:

```bash
python3 -m unittest discover -s scripts/tests
./gradlew --continue --console=plain --stacktrace :app:assembleFossDebug :app:testFossDebugUnitTest :core:ai:test :core:model:test :core:storage:test :recognition:runtime:test :recognition:models:test :recognition:strategies:test :app:assembleFossDebugAndroidTest :recognition:strategies:assembleDebugAndroidTest :app:verifyFossDebugRecognitionPackage :app:verifyFossDebugOfflineManifest
```

## Enable PR and main CI

1. In [Settings > Actions > General](https://github.com/MaxDac/fpink/settings/actions),
   enable GitHub Actions. If actions are restricted, allow the workflow's
   `actions/checkout`, `actions/setup-java`, `actions/upload-artifact`,
   `gradle/actions/setup-gradle`, and `android-actions/setup-android` actions.
   Keep workflow token permissions read-only. No repository secrets are needed.
2. Push this workflow change on a branch and open a PR targeting `main`. Wait
   for `CI` to finish and confirm the exact check name above appears and passes.
   This registers the check for selection in repository settings.
3. Configure the merge protection below. Merely committing this workflow does
   **not** block merges; GitHub settings must require its checks.
4. Merge the passing setup PR. In **Actions > CI**, confirm a new `push` run
   checks the resulting commit on `main`, with its job and reports.
   There is no second main-only workflow to enable.
5. Once the workflow exists on `main`, use **Actions > CI > Run workflow** for
   manual diagnostics. A manual run on `main` does not replace the required
   checks on a PR's latest revision.

## Block merges unless the check passes

### Recommended: a branch ruleset

1. Open [Settings > Rules > Rulesets](https://github.com/MaxDac/fpink/settings/rules)
   and select **New ruleset > New branch ruleset**. Name it `main-ci`, set
   **Enforcement status: Active**, and target the branch `main`.
2. Leave the **Bypass list empty**, including administrators, GitHub Apps and
   Dependabot, if every PR must pass. Administrators who can edit repository
   rules can still change the policy; a workflow cannot prevent that.
3. Enable **Require a pull request before merging**. Reviews/approval counts
   are separate policy choices; requiring zero approvals still requires a PR
   and does not weaken the required CI checks.
4. Enable **Require status checks to pass before merging**. Add
   `Build and JVM unit tests`, `Build (replay)`, `Build (replay, release checks)`
   and `Build (fdroid build)`. Remove any old `Native C++ unit tests`,
   `Build (replay, all cores)` and `Build (replay, 2 threads, release checks)`
   requirements: those jobs no longer exist. Select **GitHub Actions**
   as the expected source where offered. Do not require the old `build` check or
   use the workflow name `CI` instead.
5. Enable **Require branches to be up to date before merging**. Do not enable
   an exemption from required checks when creating a matching branch, if shown.
   Enable **Block force pushes** and **Restrict deletions** for `main`.
   Save the active ruleset.
6. Verify on a disposable PR that pending/failing checks disable merging. For a
   negative test, introduce an ordinary failing unit assertion on that PR,
   observe the failed required check, then fix it and push again. The check
   must pass on the latest revision before merging. Never merge the deliberately
   broken revision. The PR requirement also prevents normal direct pushes to
   `main`.

Post-merge `main` CI detects regressions; it cannot undo or reject a commit
already merged. The required PR checks and up-to-date rule are the pre-merge gate.

### Alternative: classic branch protection

Instead of a ruleset, use **Settings > Branches > Add branch protection rule**
targeting `main`. Require PRs, require the named status check, and require
branches to be up to date. Enable **Do not allow bypassing the above settings**
(or the equivalent administrator-enforcement option). Leave force pushes and
deletions disabled, and do not configure actors allowed to bypass required PRs.
Save and perform the same blocked/passing-PR smoke test. Prefer one mechanism
rather than accidentally stacking conflicting rules.

## Troubleshooting and later changes

- **Check missing from the selector:** first complete a run that emits the exact
  new job name, then refresh settings. Remove the obsolete `build` requirement
  when migrating to these checks. Never disable required checks just to merge a
  failing change.
- **Older PR still has old checks:** update/rebase its branch with the workflow
  change, or trigger a new qualifying PR run. Rerunning an old run can reuse the
  old workflow definition.
- **Expected check stays pending:** confirm Actions/action-owner policies allow
  the workflow, approve a fork run if GitHub requires it, update stale branches,
  and avoid commit-message CI-skip directives. The workflow deliberately has no
  path filters and does not skip drafts.
- **Build failed without reports:** inspect setup and compiler logs; test reports
  cannot exist if those tasks never ran.
- **Merge queue:** the `merge_group` trigger already emits the same required
  checks. Enabling a merge queue is optional and is not done by this change.
- **Renaming checks:** coordinate job-name changes with the settings update.
  Keeping a requirement for a name no longer emitted will block merges.

GitHub references: [creating rulesets](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/creating-rulesets-for-a-repository),
[available rules](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/available-rules-for-rulesets),
and [workflow events](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows).

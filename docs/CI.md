# Continuous integration

The shared [CI workflow](../.github/workflows/ci.yml) runs on pull requests
targeting `main`, every push to `main`, manual dispatches, and merge-queue
`checks_requested` events targeting `main`. It does not deploy or publish releases.

## Required checks and coverage

Configure **both job names**, not the workflow title `CI`, as required checks:

| Required check | Coverage |
|---|---|
| `Build and JVM unit tests` | Debug/release production builds, configured JVM unit tests, Android lint, pinned Paddle assets, both recognition APK package checks, and compilation of both instrumentation APKs |
| `Native C++ unit tests` | Host C++ geometry, coordinates, BGR sampling and CTC decoding unit tests |

The native job also repeats its suite with an extra compiler symlink in PATH,
guarding against multiple executable matches being treated as one command.

The jobs run independently on Ubuntu 24.04. The Android job uses JDK 17, the
checked-in Gradle wrapper, Android API 36, NDK 28.2.13676358 and CMake 3.22.1.
AGP selects any additional SDK Build Tools it requires.

Gradle `build` already runs the configured JVM tests in `:app` (debug local
tests), `:core:model`, `:core:ai`, and `:core:storage`. `:recognition:paddle`
currently has no JVM test sources. The workflow uses one Gradle invocation with
`--continue`, so independent tasks can finish after a failure without turning
that failure into success. It builds only the public `foss` product flavor
(never `full`, which is reserved for the maintainer's private companion repo —
see `docs/ARCHITECTURE.md`) and retains lint, `verifyPaddleArtifacts`,
`verifyFossDebugRecognitionPackage`, and `verifyFossReleaseRecognitionPackage`.

No emulator, device, connected test, or E2E suite is executed. Compiling
instrumentation APKs does **not** mean those tests passed. Host checks do not
prove real OCR quality, camera/chooser behavior, Keystore behavior, Android
filesystem durability, ARM64 runtime execution, or 16 KB-device compatibility.
Those checks remain separate; see the [Paddle validation guide](../recognition/paddle/README.md).
The runtime-normalization test also remains separate because it requires an
unbundled original upstream binary and an AArch64 linker.

The workflow uses read-only repository permissions and does not need
credentials for any recognition provider, signing secrets, deployment
environments, or self-hosted runners.
Gradle cache writes are limited to push/manual runs on `main`; PRs only read
caches. New commits cancel older runs of the same PR. Main pushes, manual runs,
and merge-group runs have separate concurrency groups and do not cancel each
other. Neither required job skips drafts or documentation-only PRs.

## Reports

Open **Actions > CI > a run** and inspect each job's logs. The Android job
uploads a `verification-reports` artifact after success or failure containing
the reports that were produced:

- JVM HTML reports under each module's `build/reports/tests/`.
- JUnit XML under each module's `build/test-results/`.
- Android lint reports under each module's `build/reports/lint-results*`.

Reports expire after 14 days. Early setup/compilation failures may produce no
reports; the upload warns and the original job failure is preserved. Native
test results are in the `Native C++ unit tests` job log. No APKs are published.

## Reproduce locally

Set `JAVA_HOME` to a JDK 17 installation and `ANDROID_HOME` to your Android SDK,
with the platform/NDK/CMake versions above installed and SDK licenses accepted.
Do not commit machine-specific paths. From the repository root on Windows:

```powershell
.\gradlew.bat --continue --console=plain --stacktrace :app:assembleFossDebug :app:testFossDebugUnitTest :core:ai:test :core:model:test :core:storage:test :recognition:paddle:test :app:assembleFossDebugAndroidTest :recognition:paddle:assembleDebugAndroidTest :app:verifyFossDebugRecognitionPackage
.\recognition\paddle\scripts\test-geometry.ps1 -Cxx '<path-to-clang++.exe>'
```

On Linux:

```bash
./gradlew --continue --console=plain --stacktrace :app:assembleFossDebug :app:testFossDebugUnitTest :core:ai:test :core:model:test :core:storage:test :recognition:paddle:test :app:assembleFossDebugAndroidTest :recognition:paddle:assembleDebugAndroidTest :app:verifyFossDebugRecognitionPackage
pwsh -File ./recognition/paddle/scripts/test-geometry.ps1 -Cxx g++
```

For the F-Droid-compatible source-runtime path, provide the Android NDK and
run:

```bash
ANDROID_NDK_ROOT="$ANDROID_SDK_ROOT/ndk/28.2.13676358" \
  ./gradlew --no-daemon --console=plain \
  -PbuildPaddleRuntimeFromSource \
  :recognition:paddle:verifyPaddleArtifacts \
  :recognition:paddle:assembleRelease
```

This source build is intentionally separate from ordinary CI until the pinned
Paddle Lite build has been qualified on the F-Droid build image. It must not
silently fall back to the checked-in runtime.

The native runner needs a host GCC-compatible C++17 compiler (`g++` or `clang++`).
It keeps assertions enabled and fails on compilation errors or test failures.
It does not load the Android-only Paddle runtime or require an Android SDK.

## Enable PR and main CI

1. In [Settings > Actions > General](https://github.com/MaxDac/fpink/settings/actions),
   enable GitHub Actions. If actions are restricted, allow the workflow's
   `actions/checkout`, `actions/setup-java`, `actions/upload-artifact`,
   `gradle/actions/setup-gradle`, and `android-actions/setup-android` actions.
   Keep workflow token permissions read-only. No repository secrets are needed.
2. Push this workflow change on a branch and open a PR targeting `main`. Wait
   for `CI` to finish and confirm both exact check names above appear and pass.
   This registers the new checks for selection in repository settings.
3. Configure the merge protection below. Merely committing this workflow does
   **not** block merges; GitHub settings must require its checks.
4. Merge the passing setup PR. In **Actions > CI**, confirm a new `push` run
   checks the resulting commit on `main`, with both jobs and their reports.
   There is no second main-only workflow to enable.
5. Once the workflow exists on `main`, use **Actions > CI > Run workflow** for
   manual diagnostics. A manual run on `main` does not replace the required
   checks on a PR's latest revision.

## Block merges unless both checks pass

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
4. Enable **Require status checks to pass before merging**. Add both
   `Build and JVM unit tests` and `Native C++ unit tests`. Select **GitHub Actions**
   as the expected source where offered. Do not require the old `build` check or
   use the workflow name `CI` instead.
5. Enable **Require branches to be up to date before merging**. Do not enable
   an exemption from required checks when creating a matching branch, if shown.
   Enable **Block force pushes** and **Restrict deletions** for `main`.
   Save the active ruleset.
6. Verify on a disposable PR that pending/failing checks disable merging. For a
   negative test, introduce an ordinary failing unit assertion on that PR,
   observe the failed required check, then fix it and push again. Both checks
   must pass on the latest revision before merging. Never merge the deliberately
   broken revision. The PR requirement also prevents normal direct pushes to
   `main`.

Post-merge `main` CI detects regressions; it cannot undo or reject a commit
already merged. The required PR checks and up-to-date rule are the pre-merge gate.

### Alternative: classic branch protection

Instead of a ruleset, use **Settings > Branches > Add branch protection rule**
targeting `main`. Require PRs, require both named status checks, and require
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

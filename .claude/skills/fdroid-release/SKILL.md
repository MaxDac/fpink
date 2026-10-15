---
name: fdroid-release
description: Cut an FPInk release end to end and ship it to F-Droid. Declares the version in a release-bump PR, runs the Release workflow, verifies the signed GitHub release, points this repo's recipe mirror at the new tag and checks that F-Droid's checkupdates picks it up (FPInk is merged in fdroiddata). Use when asked to release, cut a preview or stable version, or ship to F-Droid.
---

# FPInk release to F-Droid

Drive the whole flow with `gh`, `git` and `python3` (on Windows, run `python3`
in WSL if it isn't on the PATH). It is described in
`docs/RELEASING.md` and `docs/FDROID.md`; read them if a step fails. Report every
PR, run, tag and commit you create.

Inputs, taken from the prompt:
- **type**: `prerelease` (default) or `stable`. A stable release has no
  suffix (`0.1.0`, then `0.2.0`...) and becomes GitHub's Latest release; use the
  same type in steps 2 and 3.
- **version**: optional; leave it out so the tooling picks the next version.

## Guardrails

- Never move, delete or replace a tag, release or asset. Never reuse a versionCode.
- Never force-push to `main`. Never push to `fdroid/fdroiddata`; changes there go
  through a merge request from a fork.
- Never bypass required checks (`--admin`) or edit `version.properties` by hand.
- If a check or run fails, stop, show the failing log (`gh run view <id> --log-failed`)
  and propose a fix. Do not retry by publishing a different version.

## 1. Preflight

```bash
gh auth status
git fetch origin --tags --prune
git switch --detach origin/main
gh run list --branch main --workflow ci.yml --limit 1 --json conclusion,headSha   # must be success at origin/main
git tag --sort=-v:refname | head -3
git log --oneline "$(git tag --sort=-v:refname | head -1)"..origin/main           # what ships
```

If nothing user-visible changed since the last tag, say so and ask before continuing.

## 2. Release-bump PR

```bash
python3 scripts/release_version.py --repository MaxDac/fpink --release-type <type> [--requested-version <version>] --prepare
```

It prints the tag and versionCode and creates an empty
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`. Write that changelog
for end users:
- at most 500 characters, plain text, truthful;
- summarise features and fixes from the commits above;
- mention "dependency updates" only as a group; skip CI, docs and tooling changes.

```bash
V=<versionName>; git switch -c "release/$V"
git add version.properties fastlane/metadata/android/en-US/changelogs/
git commit -m "Release $V"
git push -u origin HEAD
gh pr create --title "Release $V" --body "Declare $V (versionCode <code>) for the Release workflow and F-Droid auto-update."
gh pr merge --squash --auto --delete-branch
```

Wait until it merges. The required checks include the reproducible F-Droid build,
which takes about 20 minutes. `strict` checks mean you must rebase if `main` moves.

```bash
gh pr checks --watch --required --interval 60
until [ "$(gh pr view --json state -q .state)" = MERGED ]; do sleep 30; done
```

## 3. Publish

```bash
git fetch origin; MAIN=$(git rev-parse origin/main)
gh workflow run release.yml --ref main -f release_type=<type> [-f version=$V] -F publish=true
sleep 10; RUN=$(gh run list --workflow release.yml --limit 1 --json databaseId,headSha -q ".[] | select(.headSha==\"$MAIN\") | .databaseId")
gh run watch "$RUN" --exit-status --interval 60      # about 20 minutes
gh release view "v$V" --json tagName,isPrerelease,assets -q '{tagName,isPrerelease,assets:[.assets[].name]}'
```

The `publish` job waits for the `release` environment. If it stays `waiting`,
ask the user to approve the deployment in the run page; don't try to bypass it.
`isPrerelease` must be `false` for a stable release.

Confirm that the release has `FPInk-$V.apk`, `SHA256SUMS`, `release-manifest.json` and `LICENSE`,
and that `git rev-parse "v$V^{commit}"` equals `$MAIN`.

## 4. Update the in-repo mirror

FPInk is merged in fdroiddata (fdroid/fdroiddata!50122), so checkupdates finds the
new tag on its own. Nothing is pushed to fdroiddata for a routine release. Keep the
mirror on the latest release:

```bash
git switch -c "fdroid-mirror/$V" origin/main
python3 scripts/fdroid_mr_bump.py --tag latest      # must resolve to v$V
git commit -am "Point F-Droid mirror recipe at v$V" && git push -u origin HEAD
gh pr create --fill && gh pr merge --squash --auto --delete-branch
```

If the mirror's build entry (`sudo`, `prebuild`, `build`, `ndk`, `rm`, ...)
changed since the previous release, checkupdates would copy the old steps, so the
release also needs an fdroiddata MR. Don't open it yourself: tell the user and
point to "New releases (since the merge)" in `docs/FDROID.md`.

## 5. Check F-Droid

Report where to watch the update: the
[recipe history](https://gitlab.com/fdroid/fdroiddata/-/commits/master/metadata/com.fpink.capture.yml),
where the bot commits `Update FPInk to <V>`, and the
[package page](https://f-droid.org/packages/com.fpink.capture/). Both usually lag the
release by days, so don't wait for them.

## Done

Report the release-bump PR, the release URL, the tag and SHA, the run, and the mirror PR.

---
name: fdroid-release
description: Cut an FPInk release end to end and ship it to F-Droid. Declares the version in a release-bump PR, runs the Release workflow, verifies the signed GitHub release, then points the open fdroiddata merge request (MaxDac/fdroiddata@com.fpink.capture) and this repo's recipe mirror at the new tag. Use when asked to release, cut a preview or stable version, ship to F-Droid, or update the F-Droid MR/GitLab branch to the latest version.
---

# FPInk release to F-Droid

Drive the whole flow with `gh`, `git` and `python3`. It is described in
`docs/RELEASING.md` and `docs/FDROID.md`; read them if a step fails. Report every
PR, run, tag and commit you create.

Inputs, taken from the prompt:
- **type**: `prerelease` (default) or `stable`.
- **version**: optional; leave it out so the tooling picks the next version.
- **mode**: whether to stop after the F-Droid update or also post the MR comment.
  Default: draft the comment only.

## Guardrails

- Never move, delete or replace a tag, release or asset. Never reuse a versionCode.
- Never force-push to `main`, the fdroiddata fork or `fdroid/fdroiddata`. Only push
  to the fork branch `com.fpink.capture`.
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

Confirm that the release has `FPInk-$V.apk`, `SHA256SUMS`, `release-manifest.json` and `LICENSE`,
and that `git rev-parse "v$V^{commit}"` equals `$MAIN`.

## 4. Point the fdroiddata MR at the release

The `fdroid-mr` job in the same run does this when `FDROIDDATA_GITLAB_TOKEN` is set:

```bash
gh run view "$RUN" --json jobs -q '.jobs[] | select(.name=="fdroid-mr") | .conclusion'
git ls-remote git@gitlab.com:MaxDac/fdroiddata.git refs/heads/com.fpink.capture
```

If the job only warned (no token), push it over SSH yourself. On Windows, the GitLab
SSH key may only be set up in WSL; if `git ls-remote` fails with `publickey`, run
this block inside `wsl -e bash -lc '...'`:

```bash
W=$(mktemp -d)
git clone -q --single-branch --branch com.fpink.capture git@gitlab.com:MaxDac/fdroiddata.git "$W/fdd"
python3 scripts/fdroid_mr_bump.py --tag "v$V" --commit-style sha --metadata "$W/fdd/metadata/com.fpink.capture.yml"
cd "$W/fdd"
fdroid rewritemeta com.fpink.capture && fdroid lint com.fpink.capture   # if fdroidserver is installed
git diff --stat      # expect only metadata/com.fpink.capture.yml, 5 lines
git commit -qam "FPInk: update to v$V" && git push origin HEAD:com.fpink.capture
```

The script must run against a checkout that has the new tag (`git fetch --tags`
first). Keep its `MR note:` line.

## 5. Update the in-repo mirror

```bash
git switch -c "fdroid-mirror/$V" origin/main
python3 scripts/fdroid_mr_bump.py --tag latest      # must resolve to v$V
git commit -am "Point F-Droid mirror recipe at v$V" && git push -u origin HEAD
gh pr create --fill && gh pr merge --squash --auto --delete-branch
```

## 6. Tell the F-Droid reviewer

Draft this comment for https://gitlab.com/fdroid/fdroiddata/-/merge_requests/50122:

```text
Updated to v<V> (commit <full SHA>, versionCode <code>), the latest release. <One sentence on what changed.> The build block, CurrentVersion and CurrentVersionCode were rewritten; rewritemeta and lint pass.
```

Post it only if the prompt asks you to and an authenticated `glab` is available:
`glab mr note 50122 -R fdroid/fdroiddata -m "<comment>"`. Otherwise give the
draft to the user.

## Done

Report the release URL, the tag and SHA, the fdroiddata fork commit, the mirror PR
and the MR comment. After F-Droid merges the MR, checkupdates takes over. Delete the
`fdroid-mr` job, its secret and steps 4 to 6 of this skill.

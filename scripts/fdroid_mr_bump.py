#!/usr/bin/env python3
"""Point an F-Droid recipe at a new FPInk release tag.

While the fdroiddata merge request is unmerged, checkupdates does not run, so
every new release must be pushed to the MR (the Release workflow's fdroid-mr job
does it with the exact published tag; ``--tag latest`` picks it locally). This rewrites the single
build block (versionName, versionCode, commit) and CurrentVersion(Code) in
place, keeping the rewritemeta layout. The mirror in this repository uses the
tag as ``commit``; the fdroiddata fork uses the full SHA (``--commit-style sha``).
For the fork, the whole build block is first replaced with the mirror's block
at the tag, so build steps (sudo, rm, prebuild, ...) never drift from the source.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
MIRROR_PATH = "metadata/com.fpink.capture.yml"
DEFAULT_METADATA = ROOT / MIRROR_PATH
CHANGELOG = "fastlane/metadata/android/en-US/changelogs/{code}.txt"
# Same pattern as the recipe's UpdateCheckMode, so we pick what checkupdates would.
RELEASE_TAG = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?")

FIELDS = {
    "versionName": re.compile(r"^(  - versionName: )([^\r\n]*)(?=\r?$)", re.MULTILINE),
    "versionCode": re.compile(r"^(    versionCode: )([^\r\n]*)(?=\r?$)", re.MULTILINE),
    "commit": re.compile(r"^(    commit: )([^\r\n]*)(?=\r?$)", re.MULTILINE),
    "CurrentVersion": re.compile(r"^(CurrentVersion: )([^\r\n]*)(?=\r?$)", re.MULTILINE),
    "CurrentVersionCode": re.compile(r"^(CurrentVersionCode: )([^\r\n]*)(?=\r?$)", re.MULTILINE),
}


# The Builds list: the header plus every following two-space-indented line.
BUILDS = re.compile(r"^Builds:\r?\n((?:  [^\r\n]*(?:\r?\n|$))+)", re.MULTILINE)
BUILD_ENTRY = re.compile(r"^  - versionName:", re.MULTILINE)


class BumpError(Exception):
    pass


def build_block(text: str, label: str) -> re.Match:
    matches = list(BUILDS.finditer(text))
    if len(matches) != 1:
        raise BumpError(f"{label}: expected exactly one 'Builds:' list, found {len(matches)}")
    entries = len(BUILD_ENTRY.findall(text))
    if entries != 1 or not BUILD_ENTRY.search(matches[0].group(1)):
        raise BumpError(f"{label}: expected exactly one build entry, found {entries}")
    return matches[0]


def sync_build(text: str, mirror: str) -> str:
    """Return ``text`` with its single build entry replaced by ``mirror``'s.

    The entry keeps ``text``'s versionName/versionCode/commit, so ``bump`` still
    checks the new release against what the recipe currently ships.
    """
    target = build_block(text, "recipe")
    block = build_block(mirror, "mirror").group(1).replace("\r\n", "\n")
    for key in ("versionName", "versionCode", "commit"):
        current = FIELDS[key].search(target.group(1))
        if current is None or len(FIELDS[key].findall(block)) != 1:
            raise BumpError(f"expected exactly one '{key}' line in each build entry")
        block = FIELDS[key].sub(lambda m, v=current.group(2): m.group(1) + v, block, count=1)
    if "\r\n" in target.group(1):
        block = block.replace("\n", "\r\n")
    if not block.endswith("\n"):
        block += "\r\n" if "\r\n" in target.group(1) else "\n"
    return text[: target.start(1)] + block + text[target.end(1):]


def bump(text: str, version_name: str, version_code: int, commit: str) -> str:
    """Return ``text`` pointing at the given release; reject downgrades."""
    for key, pattern in FIELDS.items():
        count = len(pattern.findall(text))
        if count != 1:
            raise BumpError(f"expected exactly one '{key}' line, found {count}")

    current_code = FIELDS["CurrentVersionCode"].search(text).group(2).strip()
    if not current_code.isdigit():
        raise BumpError(f"CurrentVersionCode is not an integer: {current_code!r}")
    values = {
        "versionName": version_name,
        "versionCode": str(version_code),
        "commit": commit,
        "CurrentVersion": version_name,
        "CurrentVersionCode": str(version_code),
    }
    if version_code < int(current_code) or (
        version_code == int(current_code)
        and FIELDS["versionName"].search(text).group(2).strip() != version_name
    ):
        raise BumpError(
            f"versionCode {version_code} ({version_name}) does not supersede "
            f"current versionCode {current_code}"
        )

    for key, pattern in FIELDS.items():
        text = pattern.sub(lambda m, v=values[key]: m.group(1) + v, text, count=1)
    return text


def git(root: Path, *arguments: str) -> str:
    result = subprocess.run(
        ["git", *arguments], cwd=root, capture_output=True, text=True, check=False
    )
    if result.returncode != 0:
        raise BumpError(f"git {' '.join(arguments)} failed: {result.stderr.strip()}")
    return result.stdout


def parse_properties(text: str) -> tuple[str, int]:
    props = dict(
        line.split("=", 1) for line in text.splitlines() if "=" in line and not line.startswith("#")
    )
    try:
        name = props["versionName"].strip()
        code = props["versionCode"].strip()
    except KeyError as missing:
        raise BumpError(f"version.properties lacks {missing}") from None
    if not code.isdigit():
        raise BumpError(f"versionCode is not an integer: {code!r}")
    return name, int(code)


def resolve_release(root: Path, tag: str) -> tuple[str, int, str]:
    name, code = parse_properties(git(root, "show", f"{tag}:version.properties"))
    if tag != f"v{name}":
        raise BumpError(f"tag {tag} does not match version.properties versionName {name}")
    sha = git(root, "rev-parse", f"{tag}^{{commit}}").strip()
    changelog = CHANGELOG.format(code=code)
    if not git(root, "show", f"{tag}:{changelog}").strip():
        raise BumpError(f"{changelog} is empty at {tag}")
    return name, code, sha


def latest_tag(root: Path) -> str:
    """Return the release tag with the highest versionCode (not the newest date)."""
    best = None
    for tag in git(root, "tag", "--list", "v*").split():
        if not RELEASE_TAG.fullmatch(tag):
            continue
        try:
            name, code = parse_properties(git(root, "show", f"{tag}:version.properties"))
        except BumpError:
            continue
        if tag == f"v{name}" and (best is None or code > best[0]):
            best = (code, tag)
    if best is None:
        raise BumpError("no release tag declares its version in version.properties")
    return best[1]


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--tag", required=True,
        help="Release tag, e.g. v0.1.0-preview.9, or 'latest' for the highest versionCode",
    )
    parser.add_argument(
        "--metadata", type=Path, default=DEFAULT_METADATA,
        help="Recipe to rewrite (default: this repository's mirror)",
    )
    parser.add_argument(
        "--commit-style", choices=("tag", "sha"), default="tag",
        help="Write the tag (mirror) or its full SHA (fdroiddata fork) as commit",
    )
    parser.add_argument("--source", type=Path, default=ROOT, help="FPInk git checkout with the tag")
    arguments = parser.parse_args(argv)

    try:
        if arguments.tag == "latest":
            arguments.tag = latest_tag(arguments.source)
        name, code, sha = resolve_release(arguments.source, arguments.tag)
        commit = arguments.tag if arguments.commit_style == "tag" else sha
        original = arguments.metadata.read_bytes().decode("utf-8")
        updated = original
        if arguments.commit_style == "sha":
            mirror = git(arguments.source, "show", f"{arguments.tag}:{MIRROR_PATH}")
            updated = sync_build(updated, mirror)
        updated = bump(updated, name, code, commit)
    except (BumpError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1

    arguments.metadata.write_bytes(updated.encode("utf-8"))
    state = "unchanged" if updated == original else "updated"
    print(f"{arguments.metadata}: {state} -> {name} (versionCode {code}, commit {commit})")
    print(f"MR note: {arguments.tag} / {sha} / versionName {name} / versionCode {code}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

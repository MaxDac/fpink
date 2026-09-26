#!/usr/bin/env python3
"""Point an F-Droid recipe at a new FPInk release tag.

While the fdroiddata merge request is unmerged, checkupdates does not run, so
every new release must be pushed to the MR by hand. This rewrites the single
build block (versionName, versionCode, commit) and CurrentVersion(Code) in
place, keeping the rewritemeta layout. The mirror in this repository uses the
tag as ``commit``; the fdroiddata fork uses the full SHA (``--commit-style sha``).
"""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_METADATA = ROOT / "metadata" / "com.fpink.capture.yml"
CHANGELOG = "fastlane/metadata/android/en-US/changelogs/{code}.txt"

FIELDS = {
    "versionName": re.compile(r"^(  - versionName: )([^\r\n]*)(?=\r?$)", re.MULTILINE),
    "versionCode": re.compile(r"^(    versionCode: )([^\r\n]*)(?=\r?$)", re.MULTILINE),
    "commit": re.compile(r"^(    commit: )([^\r\n]*)(?=\r?$)", re.MULTILINE),
    "CurrentVersion": re.compile(r"^(CurrentVersion: )([^\r\n]*)(?=\r?$)", re.MULTILINE),
    "CurrentVersionCode": re.compile(r"^(CurrentVersionCode: )([^\r\n]*)(?=\r?$)", re.MULTILINE),
}


class BumpError(Exception):
    pass


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


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--tag", required=True, help="Release tag, e.g. v0.1.0-preview.9")
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
        name, code, sha = resolve_release(arguments.source, arguments.tag)
        commit = arguments.tag if arguments.commit_style == "tag" else sha
        original = arguments.metadata.read_bytes().decode("utf-8")
        updated = bump(original, name, code, commit)
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

#!/usr/bin/env python3
"""Resolve a manual stable release or prerelease without publishing artifacts.

Published APK releases require a complete schema-1 release-manifest.json asset.
Prereleases with an explicit source-only manifest reserve their tags, but do
not establish version codes or signing certificates. Missing manifests fail.
Drafts reserve their tag names but do not contribute version codes.
All published manifests must use the same signing certificate; rotation is not
supported. Resolved metadata exposes its lowercase SHA-256 fingerprint as
previousSigningCertificateSha256, or null before the first published release.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from functools import total_ordering
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from typing import Optional


APPLICATION_ID = "com.fpink.capture"
MAX_VERSION_CODE = 2_100_000_000
ROOT = Path(__file__).resolve().parent.parent
VERSION = re.compile(
    r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
)
FULL_SHA = re.compile(r"[0-9a-fA-F]{40}")
DIGEST = re.compile(r"[0-9a-fA-F]{64}")
MANIFEST_FIELDS = {
    "schemaVersion", "applicationId", "versionName", "versionCode", "tag",
    "sourceSha", "apk", "sha256", "signingCertificateSha256",
}
SOURCE_MANIFEST_FIELDS = {
    "schemaVersion", "applicationId", "versionName", "tag", "sourceSha", "sourceOnly",
}


class ReleaseVersionError(Exception):
    """Invalid release input, incomplete history, or an unsuccessful command."""


@total_ordering
@dataclass(frozen=True)
class Version:
    major: int
    minor: int
    patch: int
    prerelease: tuple[str, ...] = ()

    @classmethod
    def parse(cls, value: object, context: str = "version") -> Version:
        match = VERSION.fullmatch(value) if isinstance(value, str) else None
        if match is None:
            raise ReleaseVersionError(
                f"{context} must be X.Y.Z or X.Y.Z-prerelease, without leading zeros or build metadata"
            )
        suffix = tuple(match[4].split(".")) if match[4] else ()
        try:
            for part in suffix:
                if part.isdigit():
                    if len(part) > 1 and part.startswith("0"):
                        raise ReleaseVersionError(f"{context} has a numeric prerelease identifier with leading zeros")
                    int(part)
            return cls(*(int(match[index]) for index in (1, 2, 3)), suffix)
        except ValueError as error:
            raise ReleaseVersionError(f"{context} contains an oversized integer") from error

    @property
    def base(self) -> Version:
        return Version(self.major, self.minor, self.patch)

    def __lt__(self, other):
        if not isinstance(other, Version):
            return NotImplemented
        own_base = (self.major, self.minor, self.patch)
        other_base = (other.major, other.minor, other.patch)
        if own_base != other_base:
            return own_base < other_base
        if not self.prerelease or not other.prerelease:
            return bool(self.prerelease) and not other.prerelease
        for own, theirs in zip(self.prerelease, other.prerelease):
            if own == theirs:
                continue
            if own.isdigit() and theirs.isdigit():
                return int(own) < int(theirs)
            if own.isdigit() != theirs.isdigit():
                return own.isdigit()
            return own < theirs
        return len(self.prerelease) < len(other.prerelease)

    def next_preview(self) -> Version:
        if not self.prerelease:
            suffix = ("preview", "1")
        elif self.prerelease[-1].isdigit():
            suffix = (*self.prerelease[:-1], str(int(self.prerelease[-1]) + 1))
        else:
            suffix = (*self.prerelease, "1")
        return Version(self.major, self.minor, self.patch, suffix)

    def __str__(self) -> str:
        suffix = "-" + ".".join(self.prerelease) if self.prerelease else ""
        return f"{self.major}.{self.minor}.{self.patch}{suffix}"


def valid_sha(value: object, context: str) -> str:
    if not isinstance(value, str) or FULL_SHA.fullmatch(value) is None:
        raise ReleaseVersionError(f"{context} must be a full 40-character hexadecimal commit SHA")
    return value.lower()


def valid_code(value: object, context: str) -> int:
    if type(value) is not int or not 1 <= value <= MAX_VERSION_CODE:
        raise ReleaseVersionError(
            f"{context} must be an integer between 1 and {MAX_VERSION_CODE}"
        )
    return value


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ReleaseVersionError(f"JSON contains duplicate field {key!r}")
        result[key] = value
    return result


def invalid_constant(value):
    raise ReleaseVersionError(f"JSON contains invalid numeric constant {value}")


def read_json(text: str, context: str):
    try:
        return json.loads(
            text, object_pairs_hook=unique_object, parse_constant=invalid_constant
        )
    except (ValueError, ReleaseVersionError) as error:
        raise ReleaseVersionError(f"{context}: invalid JSON: {error}") from error


def run_command(arguments, root: Path, allowed_returncodes=(0,)):
    try:
        result = subprocess.run(
            arguments, cwd=root, check=False, capture_output=True,
            text=True, encoding="utf-8", shell=False,
        )
    except (OSError, UnicodeError) as error:
        raise ReleaseVersionError(f"Could not run {arguments[0]}: {error}") from error
    if result.returncode not in allowed_returncodes:
        raise ReleaseVersionError(
            f"{arguments[0]} command failed (exit {result.returncode}): "
            f"{result.stderr.strip() or result.stdout.strip()}"
        )
    return result


def load_baseline(path: Path):
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as error:
        raise ReleaseVersionError(f"Cannot read source baseline {path}: {error}") from error
    properties = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        key, separator, value = line.partition("=")
        key, value = key.strip(), value.strip()
        if not separator or key not in {"versionName", "versionCode"} or key in properties:
            raise ReleaseVersionError("Invalid or duplicate source baseline property")
        properties[key] = value
    if set(properties) != {"versionName", "versionCode"}:
        raise ReleaseVersionError("Source baseline requires versionName and versionCode")
    version = Version.parse(properties["versionName"], "Source baseline versionName")
    if version.prerelease:
        raise ReleaseVersionError("Source baseline versionName must be a stable X.Y.Z version")
    code_text = properties["versionCode"]
    if re.fullmatch(r"[1-9][0-9]*", code_text) is None:
        raise ReleaseVersionError("Source baseline versionCode must be a positive integer")
    try:
        code = int(code_text)
    except ValueError as error:
        raise ReleaseVersionError("Source baseline versionCode is oversized") from error
    return version, valid_code(code, "Source baseline versionCode")


class GitRepository:
    def __init__(self, root: Path):
        self.root = root

    def validate_source(self, source_sha: str):
        head = run_command(
            ["git", "rev-parse", "--verify", "HEAD^{commit}"], self.root
        ).stdout.strip()
        if valid_sha(head, "Checked-out HEAD") != source_sha:
            raise ReleaseVersionError("--source-sha must equal the checked-out HEAD commit")
        shallow = run_command(
            ["git", "rev-parse", "--is-shallow-repository"], self.root
        ).stdout.strip()
        if shallow != "false":
            raise ReleaseVersionError(
                "Release resolution requires a complete checkout (fetch-depth: 0)"
            )

    def tag_commit(self, tag: str) -> str:
        # Only validated version tags reach this method; peel annotated tags too.
        result = run_command(
            ["git", "rev-parse", "--verify", f"refs/tags/{tag}^{{commit}}"], self.root
        )
        return valid_sha(result.stdout.strip(), f"Commit for tag {tag}")

    def require_ancestor(self, historic_sha: str, source_sha: str, tag: str):
        result = run_command(
            ["git", "merge-base", "--is-ancestor", historic_sha, source_sha],
            self.root, allowed_returncodes=(0, 1),
        )
        if result.returncode == 1:
            raise ReleaseVersionError(
                f"Published release {tag} source is not an ancestor of the current source"
            )


class GitHubClient:
    def __init__(self, repository: str, root: Path):
        if not re.fullmatch(
            r"[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?/[A-Za-z0-9_.-]+",
            repository,
        ) or repository.split("/")[-1] in {".", ".."}:
            raise ReleaseVersionError("--repository must be OWNER/REPO")
        self.repository = repository
        self.root = root

    def pages(self, resource: str):
        result = run_command(
            [
                "gh", "api", "--paginate", "--slurp",
                f"repos/{self.repository}/{resource}?per_page=100",
            ],
            self.root,
        )
        pages = read_json(result.stdout, f"GitHub {resource}")
        if not isinstance(pages, list) or any(not isinstance(page, list) for page in pages):
            raise ReleaseVersionError(f"GitHub {resource} must be a paginated array of arrays")
        items = [item for page in pages for item in page]
        if any(not isinstance(item, dict) for item in items):
            raise ReleaseVersionError(f"GitHub {resource} contains a non-object entry")
        return items

    def manifest(self, release):
        tag = release["tag_name"]
        assets = release.get("assets")
        if not isinstance(assets, list) or any(not isinstance(asset, dict) for asset in assets):
            raise ReleaseVersionError(f"Published release {tag} has invalid assets")
        matches = [asset for asset in assets if asset.get("name") == "release-manifest.json"]
        if len(matches) != 1:
            raise ReleaseVersionError(
                f"Published release {tag} requires exactly one release-manifest.json asset"
            )
        asset_id = matches[0].get("id")
        if type(asset_id) is not int or asset_id <= 0:
            raise ReleaseVersionError(f"Published release {tag} has an invalid manifest asset ID")
        result = run_command(
            [
                "gh", "api", f"repos/{self.repository}/releases/assets/{asset_id}",
                "-H", "Accept: application/octet-stream",
            ],
            self.root,
        )
        return read_json(result.stdout, f"Manifest for {tag}")


def validate_identity(manifest, release_tag: str, fields):
    if not isinstance(manifest, dict) or set(manifest) != fields:
        raise ReleaseVersionError(f"Manifest for {release_tag} has missing or unexpected fields")
    if type(manifest["schemaVersion"]) is not int or manifest["schemaVersion"] != 1:
        raise ReleaseVersionError(f"Manifest for {release_tag} requires schemaVersion 1")
    if manifest["applicationId"] != APPLICATION_ID:
        raise ReleaseVersionError(f"Manifest for {release_tag} has an invalid applicationId")
    version = Version.parse(manifest["versionName"], f"Manifest for {release_tag} versionName")
    if manifest["tag"] != f"v{version}" or manifest["tag"] != release_tag:
        raise ReleaseVersionError(f"Manifest for {release_tag} tag does not match its version/release")
    source_sha = valid_sha(manifest["sourceSha"], f"Manifest for {release_tag} sourceSha")
    return version, source_sha


def validate_manifest(manifest, release_tag: str):
    version, source_sha = validate_identity(manifest, release_tag, MANIFEST_FIELDS)
    code = valid_code(manifest["versionCode"], f"Manifest for {release_tag} versionCode")
    if manifest["apk"] != f"FPInk-{version}.apk":
        raise ReleaseVersionError(f"Manifest for {release_tag} has an invalid APK filename")
    for field in ("sha256", "signingCertificateSha256"):
        value = manifest[field]
        if not isinstance(value, str) or DIGEST.fullmatch(value) is None:
            raise ReleaseVersionError(f"Manifest for {release_tag} {field} must be 64 hexadecimal characters")
    return version, code, source_sha, manifest["signingCertificateSha256"].lower()


def resolve_version(
    repository: str, source_sha: str, requested_version: Optional[str] = None,
    root: Path = ROOT, release_type: str = "stable",
):
    if release_type not in {"stable", "prerelease"}:
        raise ReleaseVersionError("Release type must be stable or prerelease")
    source_sha = valid_sha(source_sha, "--source-sha")
    requested = (
        Version.parse(requested_version, "--requested-version")
        if requested_version not in (None, "") else None
    )
    if requested is not None and bool(requested.prerelease) != (release_type == "prerelease"):
        raise ReleaseVersionError("Requested version suffix must match the selected release type")
    github = GitHubClient(repository, root)
    git = GitRepository(root)
    git.validate_source(source_sha)
    baseline_version, baseline_code = load_baseline(root / "version.properties")
    releases = github.pages("releases")
    tags = github.pages("tags")

    tag_commits = {}
    for tag in tags:
        name = tag.get("name")
        commit = tag.get("commit")
        if not isinstance(name, str) or not name or not isinstance(commit, dict):
            raise ReleaseVersionError("GitHub tag has an invalid name or commit")
        if name in tag_commits:
            raise ReleaseVersionError(f"GitHub returned duplicate tag {name}")
        tag_commits[name] = valid_sha(commit.get("sha"), f"GitHub tag {name} commit")

    reserved_tags = set(tag_commits)
    release_ids = set()
    release_tags = set()
    published = []
    source_previews = set()

    def verify_tag(tag, expected_sha):
        if tag not in tag_commits:
            raise ReleaseVersionError(f"Published release {tag} is missing its GitHub tag")
        historic_sha = tag_commits[tag]
        if expected_sha != historic_sha or historic_sha != git.tag_commit(tag):
            raise ReleaseVersionError(f"Published release {tag} sourceSha does not match its actual tag commit")
        git.require_ancestor(historic_sha, source_sha, tag)

    for release in releases:
        release_id = release.get("id")
        tag = release.get("tag_name")
        if type(release_id) is not int or release_id <= 0 or release_id in release_ids:
            raise ReleaseVersionError("GitHub returned an invalid or duplicate release ID")
        if not isinstance(tag, str) or not tag:
            raise ReleaseVersionError("GitHub release has an invalid tag_name")
        if tag in release_tags:
            raise ReleaseVersionError(f"GitHub returned duplicate release tag {tag}")
        if type(release.get("draft")) is not bool or type(release.get("prerelease")) is not bool:
            raise ReleaseVersionError(f"GitHub release {tag} requires draft and prerelease flags")
        release_ids.add(release_id)
        release_tags.add(tag)
        reserved_tags.add(tag)
        if not release["draft"]:
            manifest = github.manifest(release)
            if isinstance(manifest, dict) and manifest.get("sourceOnly") is True:
                preview, historic_sha = validate_identity(manifest, tag, SOURCE_MANIFEST_FIELDS)
                if not release["prerelease"] or not preview.prerelease:
                    raise ReleaseVersionError("A source-only preview requires a prerelease flag and version suffix")
                if len(release["assets"]) != 1:
                    raise ReleaseVersionError("A source-only preview cannot contain assets other than its manifest")
                verify_tag(tag, historic_sha)
                source_previews.add(preview)
                continue
            published.append((release, manifest))

    versions = set()
    codes = set()
    previous_signing_certificate = None
    for release, manifest in published:
        tag = release["tag_name"]
        version, code, historic_sha, signing_certificate = validate_manifest(manifest, tag)
        if release["prerelease"] != bool(version.prerelease):
            raise ReleaseVersionError(f"Published release {tag} prerelease flag does not match its version suffix")
        if previous_signing_certificate is not None and signing_certificate != previous_signing_certificate:
            raise ReleaseVersionError(
                f"Published release {tag} has a different signing certificate; "
                "signing certificate rotation is unsupported"
            )
        previous_signing_certificate = signing_certificate
        if version in versions or code in codes:
            raise ReleaseVersionError(f"Published release {tag} has a duplicate version or versionCode")
        verify_tag(tag, historic_sha)
        versions.add(version)
        codes.add(code)

    all_versions = versions | source_previews
    stable_versions = {version for version in versions if not version.prerelease}
    highest_stable = max(stable_versions) if stable_versions else None
    highest = max(all_versions) if all_versions else None
    if requested is not None:
        version = requested
    else:
        version = (
            Version(highest_stable.major, highest_stable.minor + 1, 0)
            if highest_stable is not None else baseline_version
        )
        if highest is not None and highest.prerelease and highest.base > version:
            version = highest.base
        if release_type == "prerelease":
            matching = [v for v in all_versions if v.prerelease and v.base == version]
            version = (max(matching) if matching else version).next_preview()
    if version.base < baseline_version:
        raise ReleaseVersionError("Resolved version is below the source baseline")
    tag = f"v{version}"
    if tag in reserved_tags or str(version) in reserved_tags:
        raise ReleaseVersionError(f"Resolved version {version} collides with an existing tag or release")
    if highest_stable is not None and version <= highest_stable:
        raise ReleaseVersionError("Resolved version must be greater than the highest published stable version")
    if highest is not None and version <= highest:
        raise ReleaseVersionError("Resolved version must be greater than the highest published version")
    code = max({baseline_code} | codes) + 1
    valid_code(code, "Next versionCode")
    return {
        "schemaVersion": 1,
        "applicationId": APPLICATION_ID,
        "versionName": str(version),
        "versionCode": code,
        "tag": tag,
        "sourceSha": source_sha,
        "previousTag": f"v{highest}" if highest is not None else None,
        "previousSigningCertificateSha256": previous_signing_certificate,
        "isPrerelease": bool(version.prerelease),
    }


def write_outputs(metadata, path: Path):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    output_path = os.environ.get("GITHUB_OUTPUT")
    if output_path:
        outputs = {
            "version_name": metadata["versionName"],
            "version_code": metadata["versionCode"],
            "tag": metadata["tag"],
            "source_sha": metadata["sourceSha"],
            "previous_tag": metadata["previousTag"] or "",
            "prerelease": str(metadata["isPrerelease"]).lower(),
        }
        with open(output_path, "a", encoding="utf-8", newline="\n") as output:
            for key, value in outputs.items():
                output.write(f"{key}={value}\n")
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8", newline="\n") as summary:
            summary.write(
                f"## Resolved {'pre-release' if metadata['isPrerelease'] else 'stable release'}\n\n"
                f"- Version: `{metadata['versionName']}` (code `{metadata['versionCode']}`)\n"
                f"- Tag: `{metadata['tag']}`\n"
                f"- Source: `{metadata['sourceSha']}`\n"
                f"- Previous release: `{metadata['previousTag'] or 'none'}`\n"
            )


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True, help="GitHub OWNER/REPO")
    parser.add_argument("--source-sha", required=True, help="Full SHA of the checked-out source commit")
    parser.add_argument("--requested-version", help="Optional version, matching the release type (no v prefix)")
    parser.add_argument("--release-type", choices=("stable", "prerelease"), default="stable")
    parser.add_argument("--output", required=True, type=Path, help="Resolved metadata JSON path")
    arguments = parser.parse_args(argv)
    try:
        metadata = resolve_version(
            arguments.repository, arguments.source_sha, arguments.requested_version,
            release_type=arguments.release_type,
        )
        write_outputs(metadata, arguments.output)
    except (ReleaseVersionError, OSError, UnicodeError) as error:
        print(f"Release version resolution failed: {error}", file=sys.stderr)
        return 1
    print(f"Resolved {metadata['tag']} (versionCode {metadata['versionCode']})")
    return 0


if __name__ == "__main__":
    sys.exit(main())

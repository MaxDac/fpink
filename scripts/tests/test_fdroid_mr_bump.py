"""Tests for fdroid_mr_bump.py; release resolution uses a throwaway git repository."""

import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

MODULE_SPEC = importlib.util.spec_from_file_location(
    "fdroid_mr_bump_under_test",
    Path(__file__).resolve().parents[1] / "fdroid_mr_bump.py",
)
bumper = importlib.util.module_from_spec(MODULE_SPEC)
sys.modules[MODULE_SPEC.name] = bumper
MODULE_SPEC.loader.exec_module(bumper)

SHA = "d" * 40
BASELINE = {
    "versionName": "0.1.0-preview.8",
    "versionCode": "8",
    "commit": "v0.1.0-preview.8",
    "CurrentVersion": "0.1.0-preview.8",
    "CurrentVersionCode": "8",
}


def pinned_mirror():
    # The real recipe layout, pinned to preview.8 so the tests don't depend on
    # which release metadata/com.fpink.capture.yml currently points at.
    text = (Path(__file__).resolve().parents[2] / "metadata" / "com.fpink.capture.yml").read_text(
        encoding="utf-8"
    )
    for key, pattern in bumper.FIELDS.items():
        text = pattern.sub(lambda m, v=BASELINE[key]: m.group(1) + v, text, count=1)
    return text


MIRROR = pinned_mirror()


def build_fields(text):
    return {key: pattern.search(text).group(2) for key, pattern in bumper.FIELDS.items()}


class BumpTextTests(unittest.TestCase):
    def test_rewrites_only_version_fields(self):
        updated = bumper.bump(MIRROR, "0.1.0-preview.9", 9, "v0.1.0-preview.9")
        self.assertEqual(
            build_fields(updated),
            {
                "versionName": "0.1.0-preview.9",
                "versionCode": "9",
                "commit": "v0.1.0-preview.9",
                "CurrentVersion": "0.1.0-preview.9",
                "CurrentVersionCode": "9",
            },
        )
        changed = [
            (old, new)
            for old, new in zip(MIRROR.splitlines(), updated.splitlines())
            if old != new
        ]
        self.assertEqual(len(changed), 5)
        self.assertEqual(len(MIRROR.splitlines()), len(updated.splitlines()))

    def test_sha_commit_style(self):
        updated = bumper.bump(MIRROR, "0.1.0-preview.9", 9, SHA)
        self.assertEqual(build_fields(updated)["commit"], SHA)

    def test_preserves_crlf(self):
        crlf = MIRROR.replace("\n", "\r\n")
        updated = bumper.bump(crlf, "0.1.0-preview.9", 9, SHA)
        self.assertEqual(updated, bumper.bump(MIRROR, "0.1.0-preview.9", 9, SHA).replace("\n", "\r\n"))

    def test_same_release_is_a_noop(self):
        fields = build_fields(MIRROR)
        self.assertEqual(
            bumper.bump(MIRROR, fields["versionName"], int(fields["versionCode"]), fields["commit"]),
            MIRROR,
        )

    def test_rejects_downgrade_and_reused_code(self):
        code = int(build_fields(MIRROR)["CurrentVersionCode"])
        with self.assertRaisesRegex(bumper.BumpError, "does not supersede"):
            bumper.bump(MIRROR, "0.0.9", code - 1, "v0.0.9")
        with self.assertRaisesRegex(bumper.BumpError, "does not supersede"):
            bumper.bump(MIRROR, "9.9.9", code, "v9.9.9")

    def test_rejects_multiple_build_blocks(self):
        start = MIRROR.index("  - versionName:")
        end = MIRROR.index("\nAllowedAPKSigningKeys")
        doubled = MIRROR[:end] + "\n" + MIRROR[start:end] + MIRROR[end:]
        with self.assertRaisesRegex(bumper.BumpError, "exactly one 'versionName'"):
            bumper.bump(doubled, "0.1.0-preview.9", 9, SHA)


def stale_fork(text=MIRROR):
    # A fork recipe that kept build steps the mirror has since dropped.
    return text.replace(
        "      - foss\n",
        "      - foss\n    rm:\n      - recognition/paddle/native/lib.so\n"
        "    build: bash ../build.sh\n      build\n    ndk: 28.2.13676358\n",
        1,
    )


class SyncBuildTests(unittest.TestCase):
    def test_replaces_stale_build_entry_with_mirror(self):
        self.assertEqual(bumper.sync_build(stale_fork(), MIRROR), MIRROR)

    def test_keeps_recipe_version_fields(self):
        # The mirror at a tag still names the previous release (it is bumped after tagging).
        fork = bumper.bump(stale_fork(), "0.1.0-preview.9", 9, SHA)
        synced = bumper.sync_build(fork, MIRROR)
        self.assertEqual(synced, bumper.bump(MIRROR, "0.1.0-preview.9", 9, SHA))
        self.assertEqual(bumper.bump(synced, "0.1.0-preview.9", 9, SHA), synced)

    def test_preserves_fork_crlf(self):
        fork = stale_fork().replace("\n", "\r\n")
        self.assertEqual(bumper.sync_build(fork, MIRROR), MIRROR.replace("\n", "\r\n"))

    def test_rejects_multiple_entries(self):
        start = MIRROR.index("  - versionName:")
        end = MIRROR.index("\nAllowedAPKSigningKeys")
        doubled = MIRROR[:end] + "\n" + MIRROR[start:end] + MIRROR[end:]
        with self.assertRaisesRegex(bumper.BumpError, "mirror: expected exactly one build entry"):
            bumper.sync_build(MIRROR, doubled)
        with self.assertRaisesRegex(bumper.BumpError, "recipe: expected exactly one build entry"):
            bumper.sync_build(doubled, MIRROR)


class ResolveReleaseTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.git("init", "-q")
        self.git("config", "user.email", "t@example.invalid")
        self.git("config", "user.name", "t")

    def git(self, *arguments):
        return subprocess.run(
            ["git", *arguments], cwd=self.root, check=True, capture_output=True, text=True
        ).stdout.strip()

    def release(self, name, code, changelog="Notes\n"):
        (self.root / "version.properties").write_text(
            f"versionName={name}\nversionCode={code}\n", encoding="utf-8"
        )
        log = self.root / bumper.CHANGELOG.format(code=code)
        log.parent.mkdir(parents=True, exist_ok=True)
        log.write_text(changelog, encoding="utf-8")
        mirror = self.root / bumper.MIRROR_PATH
        mirror.parent.mkdir(parents=True, exist_ok=True)
        mirror.write_text(MIRROR, encoding="utf-8")
        self.git("add", "-A")
        self.git("commit", "-qm", name)
        self.git("tag", "-a", f"v{name}", "-m", name)
        return self.git("rev-parse", "HEAD")

    def test_resolves_tag(self):
        sha = self.release("0.1.0-preview.9", 9)
        self.assertEqual(
            bumper.resolve_release(self.root, "v0.1.0-preview.9"), ("0.1.0-preview.9", 9, sha)
        )

    def test_rejects_mismatched_tag(self):
        self.release("0.1.0-preview.9", 9)
        self.git("tag", "v0.2.0")
        with self.assertRaisesRegex(bumper.BumpError, "does not match"):
            bumper.resolve_release(self.root, "v0.2.0")

    def test_rejects_empty_changelog(self):
        self.release("0.1.0-preview.9", 9, changelog="\n")
        with self.assertRaisesRegex(bumper.BumpError, "is empty"):
            bumper.resolve_release(self.root, "v0.1.0-preview.9")

    def test_latest_picks_highest_version_code(self):
        self.git("commit", "-q", "--allow-empty", "-m", "old")
        self.git("tag", "v0.0.1")  # predates version.properties: skipped
        self.release("0.1.0-preview.9", 9)
        self.release("0.1.0-preview.10", 10)
        self.git("tag", "v0.1.0-rogue")  # name mismatch: skipped
        self.git("tag", "not-a-release")
        self.assertEqual(bumper.latest_tag(self.root), "v0.1.0-preview.10")
        recipe = self.root / "recipe.yml"
        recipe.write_bytes(MIRROR.encode("utf-8"))
        self.assertEqual(
            bumper.main(["--tag", "latest", "--metadata", str(recipe), "--source", str(self.root)]), 0
        )
        fields = build_fields(recipe.read_text(encoding="utf-8"))
        self.assertEqual((fields["versionCode"], fields["commit"]), ("10", "v0.1.0-preview.10"))

    def test_latest_without_releases_fails(self):
        self.git("commit", "-q", "--allow-empty", "-m", "old")
        self.git("tag", "v0.0.1")
        with self.assertRaisesRegex(bumper.BumpError, "no release tag"):
            bumper.latest_tag(self.root)

    def test_main_rewrites_fork_recipe_with_sha(self):
        sha = self.release("0.1.0-preview.9", 9)
        recipe = self.root / "recipe.yml"
        recipe.write_bytes(stale_fork().encode("utf-8"))
        status = bumper.main(
            ["--tag", "v0.1.0-preview.9", "--metadata", str(recipe),
             "--commit-style", "sha", "--source", str(self.root)]
        )
        self.assertEqual(status, 0)
        text = recipe.read_text(encoding="utf-8")
        self.assertEqual(build_fields(text)["commit"], sha)
        self.assertEqual(text, bumper.bump(MIRROR, "0.1.0-preview.9", 9, sha))

    def test_main_tag_style_keeps_build_steps(self):
        self.release("0.1.0-preview.9", 9)
        recipe = self.root / "recipe.yml"
        recipe.write_bytes(stale_fork().encode("utf-8"))
        bumper.main(["--tag", "v0.1.0-preview.9", "--metadata", str(recipe), "--source", str(self.root)])
        self.assertIn("    rm:\n", recipe.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()

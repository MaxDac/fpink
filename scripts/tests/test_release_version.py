"""Offline tests: Git, GitHub, properties, and workflow files are mocked."""

import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import mock_open, patch

MODULE_SPEC = importlib.util.spec_from_file_location(
    "release_version_under_test",
    Path(__file__).resolve().parents[1] / "release_version.py",
)
resolver = importlib.util.module_from_spec(MODULE_SPEC)
sys.modules[MODULE_SPEC.name] = resolver
MODULE_SPEC.loader.exec_module(resolver)


SOURCE_SHA = "a" * 40
OLD_SHA = "b" * 40
OTHER_SHA = "c" * 40


class ResolverFixture(unittest.TestCase):
    def setUp(self):
        self.release_pages = [[]]
        self.tag_pages = [[]]
        self.manifests = {}
        self.local_tags = {}
        self.ancestor_status = {}
        self.head = SOURCE_SHA
        self.shallow = "false"
        self.baseline = "versionName=0.1.0\nversionCode=1\n"
        self.command_mock = self.enterContext(
            patch.object(resolver.subprocess, "run", side_effect=self.run_command)
        )
        self.enterContext(
            patch.object(Path, "read_text", side_effect=lambda **kwargs: self.baseline)
        )

    def run_command(self, arguments, **kwargs):
        self.assertIsInstance(arguments, list)
        self.assertIs(kwargs["shell"], False)
        self.assertIs(kwargs["check"], False)
        self.assertTrue(kwargs["capture_output"])
        self.assertTrue(kwargs["text"])
        self.assertEqual(kwargs["encoding"], "utf-8")
        self.assertEqual(kwargs["cwd"], resolver.ROOT)
        stdout = ""
        returncode = 0
        if arguments == ["git", "rev-parse", "--verify", "HEAD^{commit}"]:
            stdout = self.head
        elif arguments == ["git", "rev-parse", "--is-shallow-repository"]:
            stdout = self.shallow
        elif arguments[:3] == ["git", "rev-parse", "--verify"]:
            tag_ref = arguments[3]
            self.assertTrue(tag_ref.startswith("refs/tags/v"))
            self.assertTrue(tag_ref.endswith("^{commit}"))
            tag = tag_ref[len("refs/tags/"):-len("^{commit}")]
            if tag not in self.local_tags:
                returncode = 128
            else:
                stdout = self.local_tags[tag]
        elif arguments[:3] == ["git", "merge-base", "--is-ancestor"]:
            self.assertEqual(arguments[4], SOURCE_SHA)
            returncode = self.ancestor_status.get(arguments[3], 0)
        elif arguments == [
            "gh", "api", "--paginate", "--slurp",
            "repos/example/fpink/releases?per_page=100",
        ]:
            stdout = json.dumps(self.release_pages)
        elif arguments == [
            "gh", "api", "--paginate", "--slurp",
            "repos/example/fpink/tags?per_page=100",
        ]:
            stdout = json.dumps(self.tag_pages)
        elif arguments[:2] == ["gh", "api"] and "/releases/assets/" in arguments[2]:
            self.assertEqual(arguments[3:], ["-H", "Accept: application/octet-stream"])
            asset_id = int(arguments[2].rsplit("/", 1)[1])
            manifest = self.manifests[asset_id]
            stdout = manifest if isinstance(manifest, str) else json.dumps(manifest)
        else:
            self.fail(f"Unexpected command: {arguments!r}")
        return subprocess.CompletedProcess(arguments, returncode, stdout=stdout, stderr="")

    def add_release(self, version="0.1.0", code=2, sha=OLD_SHA, draft=False,
                    prerelease=False, page=0):
        while len(self.release_pages) <= page:
            self.release_pages.append([])
        while len(self.tag_pages) <= page:
            self.tag_pages.append([])
        release_id = sum(len(items) for items in self.release_pages) + 1
        asset_id = release_id * 100
        tag = f"v{version}"
        release = {
            "id": release_id,
            "tag_name": tag,
            "draft": draft,
            "prerelease": prerelease,
            "assets": [{"id": asset_id, "name": "release-manifest.json"}],
        }
        manifest = {
            "schemaVersion": 1,
            "applicationId": "com.fpink.capture",
            "versionName": version,
            "versionCode": code,
            "tag": tag,
            "sourceSha": sha,
            "apk": f"FPInk-{version}.apk",
            "sha256": "d" * 64,
            "signingCertificateSha256": "e" * 64,
        }
        self.release_pages[page].append(release)
        self.tag_pages[page].append({"name": tag, "commit": {"sha": sha}})
        self.manifests[asset_id] = manifest
        self.local_tags[tag] = sha
        return release, manifest

    def resolve(self, requested=None, source=SOURCE_SHA, repository="example/fpink", release_type="stable"):
        return resolver.resolve_version(repository, source, requested, release_type=release_type)

    def add_source_preview(self, version="0.1.0-preview.1", sha=OLD_SHA):
        release, _ = self.add_release(version, prerelease=True, sha=sha)
        self.manifests[release["assets"][0]["id"]] = {
            "schemaVersion": 1, "applicationId": "com.fpink.capture",
            "versionName": version, "tag": f"v{version}", "sourceSha": sha, "sourceOnly": True,
        }
        return release

    def downloads(self):
        return [
            call.args[0][2] for call in self.command_mock.call_args_list
            if call.args[0][:2] == ["gh", "api"]
            and "/releases/assets/" in call.args[0][2]
        ]


class ResolverTests(ResolverFixture):
    def test_first_release_uses_baseline_and_code_two(self):
        self.assertEqual(self.resolve(), {
            "schemaVersion": 1,
            "applicationId": "com.fpink.capture",
            "versionName": "0.1.0",
            "versionCode": 2,
            "tag": "v0.1.0",
            "sourceSha": SOURCE_SHA,
            "previousTag": None,
            "previousSigningCertificateSha256": None,
            "isPrerelease": False,
        })
        self.assertEqual(self.downloads(), [])

    def test_initial_explicit_version(self):
        self.assertEqual(self.resolve("1.0.0")["versionName"], "1.0.0")

    def test_blank_dispatch_input_selects_automatic_version(self):
        self.assertEqual(self.resolve("")["versionName"], "0.1.0")
        self.assertEqual(self.resolve("", release_type="prerelease")["versionName"], "0.1.0-preview.1")

    def test_default_next_minor_resets_patch(self):
        self.add_release("2.3.99", code=42)
        metadata = self.resolve()
        self.assertEqual(metadata["versionName"], "2.4.0")
        self.assertEqual(metadata["versionCode"], 43)
        self.assertEqual(metadata["previousTag"], "v2.3.99")
        self.assertEqual(metadata["previousSigningCertificateSha256"], "e" * 64)

    def test_historical_signers_must_agree_across_all_pages(self):
        self.add_release("1.0.0", code=4)
        self.add_release("0.9.0", code=3, page=1)
        _, older_manifest = self.add_release("0.1.0", code=2, page=2)
        older_manifest["signingCertificateSha256"] = "f" * 64
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "rotation is unsupported"):
            self.resolve()
        self.assertEqual(len(self.downloads()), 3)

    def test_historical_fingerprint_is_compared_and_returned_in_lowercase(self):
        _, manifest = self.add_release("0.1.0", code=2)
        manifest["signingCertificateSha256"] = "E" * 64
        self.add_release("0.2.0", code=3, page=1)
        self.assertEqual(self.resolve()["previousSigningCertificateSha256"], "e" * 64)

    def test_draft_signer_does_not_affect_published_history(self):
        self.add_release()
        _, draft_manifest = self.add_release("1.0.0", code=3, draft=True)
        draft_manifest["signingCertificateSha256"] = "f" * 64
        self.assertEqual(self.resolve()["previousSigningCertificateSha256"], "e" * 64)
        self.assertEqual(len(self.downloads()), 1)

    def test_numeric_order_and_code_maximum_across_paginated_history(self):
        self.add_release("0.10.0", code=7)
        self.add_release("0.9.99", code=42, sha=OTHER_SHA, page=1)
        self.add_release("0.2.0", code=3, page=2)
        metadata = self.resolve()
        self.assertEqual(metadata["versionName"], "0.11.0")
        self.assertEqual(metadata["versionCode"], 43)
        self.assertEqual(metadata["previousTag"], "v0.10.0")
        self.assertEqual(len(self.downloads()), 3)

    def test_manual_patch_and_major(self):
        self.add_release("0.1.0")
        for requested in ("0.1.1", "1.0.0", "2.10.0"):
            with self.subTest(requested=requested):
                metadata = self.resolve(requested)
                self.assertEqual(metadata["versionName"], requested)
                self.assertEqual(metadata["versionCode"], 3)

    def test_code_uses_source_baseline_even_when_history_is_lower(self):
        self.baseline = "versionName=0.1.0\nversionCode=50\n"
        self.add_release(code=2)
        self.assertEqual(self.resolve()["versionCode"], 51)

    def test_requested_version_rejects_non_stable_or_injection_inputs(self):
        for value in (
            "v0.1.0", "01.1.0", "0.01.0", "0.1.00", "0.1", "0.1.0.1",
            "0.1.0-rc.1", "0.1.0+build", " 0.1.0", "0.1.0 ", "0.1.0\n",
            "0.1.0\nfoo=bar", "$(whoami)", "0.1.0; echo unsafe", "０.1.0",
            "-1.0.0", "0.1.0\r\n", "1.2.3/../../other",
        ):
            with self.subTest(value=value):
                with self.assertRaisesRegex(resolver.ReleaseVersionError, "X.Y.Z|release type"):
                    self.resolve(value)
        self.command_mock.assert_not_called()

    def test_requested_below_baseline_rejected(self):
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "below the source baseline"):
            self.resolve("0.0.9")

    def test_automatic_version_below_source_baseline_rejected(self):
        self.baseline = "versionName=2.0.0\nversionCode=1\n"
        self.add_release("1.0.0")
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "below the source baseline"):
            self.resolve()

    def test_requested_older_than_highest_stable_rejected(self):
        self.add_release("1.2.3")
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "greater than"):
            self.resolve("1.2.2")

    def test_requested_current_published_version_collides(self):
        self.add_release()
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "collides"):
            self.resolve("0.1.0")

    def test_unpublished_tag_collision(self):
        for name in ("v0.1.0", "0.1.0"):
            with self.subTest(name=name):
                self.tag_pages = [[{"name": name, "commit": {"sha": OLD_SHA}}]]
                with self.assertRaisesRegex(resolver.ReleaseVersionError, "collides"):
                    self.resolve()

    def test_draft_collision_without_a_git_tag(self):
        self.add_release(draft=True)
        self.tag_pages = [[]]
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "collides"):
            self.resolve()
        self.assertEqual(self.downloads(), [])

    def test_drafts_do_not_supply_codes_or_require_manifests(self):
        release, _ = self.add_release("5.0.0", code=2_100_000_000, draft=True)
        release["assets"] = []
        metadata = self.resolve()
        self.assertEqual(metadata["versionName"], "0.1.0")
        self.assertEqual(metadata["versionCode"], 2)
        self.assertIsNone(metadata["previousTag"])
        self.assertIsNone(metadata["previousSigningCertificateSha256"])
        self.assertEqual(self.downloads(), [])

    def test_published_prerelease_contributes_code_and_signer(self):
        self.add_release()
        self.add_release("0.2.0-rc.1", code=99, prerelease=True)
        result = self.resolve()
        self.assertEqual(result["versionName"], "0.2.0")
        self.assertEqual(result["versionCode"], 100)
        self.assertEqual(result["previousSigningCertificateSha256"], "e" * 64)
        self.assertFalse(result["isPrerelease"])
        self.assertEqual(len(self.downloads()), 2)

    def test_published_prerelease_name_requires_prerelease_flag(self):
        self.add_release("0.2.0-rc.1")
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "flag does not match"):
            self.resolve()

    def test_stable_name_cannot_be_labeled_prerelease(self):
        self.add_release(prerelease=True)
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "flag does not match"):
            self.resolve()

    def test_source_only_preview_reserves_tag_without_code_or_signer(self):
        self.add_source_preview()
        result = self.resolve(release_type="prerelease")
        self.assertEqual(result["versionName"], "0.1.0-preview.2")
        self.assertEqual(result["versionCode"], 2)
        self.assertEqual(result["previousTag"], "v0.1.0-preview.1")
        self.assertIsNone(result["previousSigningCertificateSha256"])
        self.assertTrue(result["isPrerelease"])
        self.assertEqual(len(self.downloads()), 1)
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "collides"):
            self.resolve("0.1.0-preview.1", release_type="prerelease")

    def test_stable_release_can_follow_source_only_preview(self):
        self.add_source_preview()
        result = self.resolve()
        self.assertEqual(result["versionName"], "0.1.0")
        self.assertEqual(result["versionCode"], 2)

    def test_source_only_preview_still_requires_verified_ancestral_tag(self):
        self.add_source_preview()
        self.ancestor_status[OLD_SHA] = 1
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "not an ancestor"):
            self.resolve(release_type="prerelease")

    def test_source_only_preview_requires_matching_local_tag(self):
        self.add_source_preview()
        self.local_tags["v0.1.0-preview.1"] = OTHER_SHA
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "actual tag commit"):
            self.resolve(release_type="prerelease")

    def test_source_only_preview_requires_remote_tag(self):
        self.add_source_preview()
        self.tag_pages = [[]]
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "missing its GitHub tag"):
            self.resolve()

    def test_source_only_preview_requires_valid_version_suffix(self):
        for version in ("0.1.0", "0.1.0-preview.01", "0.1.0+build", "nightly"):
            with self.subTest(version=version):
                self.release_pages, self.tag_pages = [[]], [[]]
                self.add_source_preview(version)
                with self.assertRaises(resolver.ReleaseVersionError):
                    self.resolve()

    def test_any_uploaded_preview_asset_requires_a_manifest(self):
        release = self.add_source_preview()
        for name in ("FPInk-0.1.0-preview.1.apk", "notes.txt", "SHA256SUMS"):
            with self.subTest(name=name):
                release["assets"] = [{"id": 999, "name": name}]
                with self.assertRaisesRegex(resolver.ReleaseVersionError, "exactly one release-manifest"):
                    self.resolve(release_type="prerelease")

    def test_empty_preview_without_explicit_manifest_is_rejected(self):
        release = self.add_source_preview()
        release["assets"] = []
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "exactly one release-manifest"):
            self.resolve(release_type="prerelease")

    def test_source_only_manifest_cannot_hide_apk_or_code(self):
        release = self.add_source_preview()
        release["assets"].append({"id": 999, "name": "FPInk-0.1.0-preview.1.apk"})
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "cannot contain assets"):
            self.resolve()
        release["assets"].pop()
        self.manifests[100]["versionCode"] = 2
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "fields"):
            self.resolve()

    def test_source_only_manifest_requires_literal_true(self):
        self.add_source_preview()
        for value in (False, "true", 1, None):
            with self.subTest(value=value):
                self.manifests[100]["sourceOnly"] = value
                with self.assertRaisesRegex(resolver.ReleaseVersionError, "fields"):
                    self.resolve()

    def test_automatic_preview_after_stable_release(self):
        self.add_release("1.2.3", code=8)
        result = self.resolve(release_type="prerelease")
        self.assertEqual(result["versionName"], "1.3.0-preview.1")
        self.assertEqual(result["versionCode"], 9)

    def test_automatic_preview_sequence_is_numeric(self):
        self.add_release("0.1.0-preview.9", code=2, prerelease=True)
        self.add_release("0.1.0-preview.10", code=3, prerelease=True, page=1)
        result = self.resolve(release_type="prerelease")
        self.assertEqual(result["versionName"], "0.1.0-preview.11")
        self.assertEqual(result["versionCode"], 4)

    def test_newest_preview_channel_is_advanced(self):
        self.add_source_preview()
        self.add_release("0.1.0-rc.1", code=2, prerelease=True)
        result = self.resolve(release_type="prerelease")
        self.assertEqual(result["versionName"], "0.1.0-rc.2")
        self.assertEqual(result["versionCode"], 3)

    def test_non_numeric_preview_suffix_gets_sequence(self):
        self.add_source_preview("0.1.0-beta")
        self.assertEqual(self.resolve(release_type="prerelease")["versionName"], "0.1.0-beta.1")

    def test_ahead_preview_is_not_downgraded_by_automatic_selection(self):
        self.add_release("0.1.0", code=2)
        self.add_release("1.0.0-rc.3", code=3, prerelease=True)
        self.assertEqual(self.resolve(release_type="prerelease")["versionName"], "1.0.0-rc.4")
        self.assertEqual(self.resolve()["versionName"], "1.0.0")

    def test_final_release_increments_code_after_signed_preview(self):
        self.add_release("0.1.0-preview.2", code=2, prerelease=True)
        result = self.resolve()
        self.assertEqual(result["versionName"], "0.1.0")
        self.assertEqual(result["versionCode"], 3)
        self.assertEqual(result["previousSigningCertificateSha256"], "e" * 64)

    def test_manual_preview_version(self):
        result = self.resolve("1.0.0-beta.1", release_type="prerelease")
        self.assertEqual(result["versionName"], "1.0.0-beta.1")
        self.assertTrue(result["isPrerelease"])

    def test_preview_rejects_stable_version_override(self):
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "release type"):
            self.resolve("1.0.0", release_type="prerelease")
        self.command_mock.assert_not_called()

    def test_preview_version_must_be_newer_than_stable(self):
        self.add_release("0.1.0")
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "highest published stable"):
            self.resolve("0.1.0-preview.1", release_type="prerelease")

    def test_preview_version_must_be_newer_than_previous_preview(self):
        self.add_source_preview("0.1.0-preview.10")
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "highest published version"):
            self.resolve("0.1.0-preview.9", release_type="prerelease")

    def test_preview_cannot_reset_signer_or_reuse_code(self):
        self.add_release("0.1.0", code=2)
        _, manifest = self.add_release("0.2.0-preview.1", code=3, prerelease=True)
        manifest["signingCertificateSha256"] = "f" * 64
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "rotation is unsupported"):
            self.resolve(release_type="prerelease")
        manifest["signingCertificateSha256"] = "e" * 64
        manifest["versionCode"] = 2
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "duplicate version or versionCode"):
            self.resolve(release_type="prerelease")

    def test_invalid_release_type_fails_before_network(self):
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "Release type"):
            self.resolve(release_type="production")
        self.command_mock.assert_not_called()

    def test_missing_manifest_in_any_published_release_is_fatal(self):
        self.add_release("1.0.0", code=3)
        older, _ = self.add_release("0.1.0", code=2, page=1)
        older["assets"] = []
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "exactly one release-manifest"):
            self.resolve()

    def test_duplicate_manifest_assets_are_fatal(self):
        release, _ = self.add_release()
        release["assets"].append({"id": 200, "name": "release-manifest.json"})
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "exactly one"):
            self.resolve()

    def test_invalid_manifest_asset_id(self):
        release, _ = self.add_release()
        for value in (None, True, 0, -1, "100", "../../other"):
            with self.subTest(value=value):
                release["assets"][0]["id"] = value
                with self.assertRaisesRegex(resolver.ReleaseVersionError, "asset ID"):
                    self.resolve()

    def test_invalid_assets_shape(self):
        release, _ = self.add_release()
        for value in (None, {}, "assets", [None]):
            with self.subTest(value=value):
                release["assets"] = value
                with self.assertRaisesRegex(resolver.ReleaseVersionError, "invalid assets"):
                    self.resolve()

    def test_corrupt_manifest_json(self):
        self.add_release()
        for value in ("not json", '{"versionCode":', '{"versionCode":NaN}',
                      '{"versionCode":1,"versionCode":2}'):
            with self.subTest(value=value):
                self.manifests[100] = value
                with self.assertRaisesRegex(resolver.ReleaseVersionError, "invalid JSON"):
                    self.resolve()

    def test_manifest_missing_unknown_or_non_object_schema(self):
        _, original = self.add_release()
        missing = dict(original)
        del missing["sha256"]
        unknown = dict(original, extra=True)
        for value in (None, [], {}, missing, unknown):
            with self.subTest(value=value):
                self.manifests[100] = value
                with self.assertRaisesRegex(resolver.ReleaseVersionError, "fields"):
                    self.resolve()

    def test_all_manifest_fields_are_strictly_validated(self):
        _, original = self.add_release()
        invalid_values = {
            "schemaVersion": [True, 0, 2, "1", 1.0, None],
            "applicationId": ["com.other.capture", None, 7],
            "versionName": ["v0.1.0", "00.1.0", "0.1.0-rc.1", 1, None],
            "versionCode": [True, False, 0, -1, 2_100_000_001, "2", 2.0, None],
            "tag": ["v0.2.0", "0.1.0", "v00.1.0", "v0.1.0\n", None],
            "sourceSha": ["a" * 39, "g" * 40, OLD_SHA + "\n", None, 4],
            "apk": ["FPInk.apk", "FPInk-0.2.0.apk", "../FPInk-0.1.0.apk", None],
            "sha256": ["a" * 63, "g" * 64, "a" * 65, "a" * 64 + "\n", None, 4],
            "signingCertificateSha256": [
                "e" * 63, "x" * 64, ":".join(["ee"] * 32), None, 4,
            ],
        }
        for field, values in invalid_values.items():
            for value in values:
                with self.subTest(field=field, value=value):
                    self.manifests[100] = dict(original, **{field: value})
                    with self.assertRaises(resolver.ReleaseVersionError):
                        self.resolve()

    def test_release_tag_must_match_manifest_tag(self):
        release, _ = self.add_release()
        release["tag_name"] = "v0.2.0"
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "tag does not match"):
            self.resolve()

    def test_missing_published_tag(self):
        self.add_release()
        self.tag_pages = [[]]
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "missing its GitHub tag"):
            self.resolve()

    def test_remote_tag_commit_mismatch(self):
        self.add_release()
        self.tag_pages[0][0]["commit"]["sha"] = OTHER_SHA
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "actual tag commit"):
            self.resolve()

    def test_local_tag_commit_mismatch(self):
        self.add_release()
        self.local_tags["v0.1.0"] = OTHER_SHA
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "actual tag commit"):
            self.resolve()

    def test_local_tag_must_be_present_and_peel_to_a_commit(self):
        self.add_release()
        del self.local_tags["v0.1.0"]
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "git command failed"):
            self.resolve()

    def test_annotated_tag_is_peeled(self):
        self.add_release()
        self.resolve()
        commands = [call.args[0] for call in self.command_mock.call_args_list]
        self.assertIn(
            ["git", "rev-parse", "--verify", "refs/tags/v0.1.0^{commit}"], commands
        )

    def test_every_stable_source_must_be_an_ancestor(self):
        self.add_release("1.0.0", code=3, sha=OTHER_SHA)
        self.add_release("0.1.0", code=2, sha=OLD_SHA)
        self.ancestor_status[OLD_SHA] = 1
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "not an ancestor"):
            self.resolve()

    def test_git_ancestry_command_errors_are_not_treated_as_ancestors(self):
        self.add_release()
        self.ancestor_status[OLD_SHA] = 128
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "git command failed"):
            self.resolve()

    def test_duplicate_published_codes_rejected(self):
        self.add_release("0.1.0", code=2)
        self.add_release("0.2.0", code=2)
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "duplicate version or versionCode"):
            self.resolve()

    def test_duplicate_release_tags_rejected(self):
        self.add_release()
        self.add_release(code=3)
        self.tag_pages[0].pop()
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "duplicate release tag"):
            self.resolve()

    def test_duplicate_release_ids_rejected(self):
        self.add_release()
        release, _ = self.add_release("0.2.0", code=3)
        release["id"] = 1
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "duplicate release ID"):
            self.resolve()

    def test_duplicate_tags_rejected(self):
        self.add_release()
        self.tag_pages.append(list(self.tag_pages[0]))
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "duplicate tag"):
            self.resolve()

    def test_bad_release_metadata_rejected(self):
        release, _ = self.add_release()
        initial = dict(release)
        for field, values in {
            "id": [True, 0, -1, "1", None],
            "tag_name": ["", 1, None],
            "draft": [None, 0, "false"],
            "prerelease": [None, 0, "false"],
        }.items():
            for value in values:
                with self.subTest(field=field, value=value):
                    self.release_pages = [[dict(initial, **{field: value})]]
                    with self.assertRaises(resolver.ReleaseVersionError):
                        self.resolve()

    def test_bad_tag_metadata_rejected(self):
        for tag in (
            {}, {"name": "", "commit": {"sha": OLD_SHA}},
            {"name": "v1.0.0", "commit": None},
            {"name": "v1.0.0", "commit": {"sha": "b" * 7}},
            {"name": "v1.0.0", "commit": {"sha": "z" * 40}},
        ):
            with self.subTest(tag=tag):
                self.tag_pages = [[tag]]
                with self.assertRaises(resolver.ReleaseVersionError):
                    self.resolve()

    def test_paginated_shape_is_validated_for_both_resources(self):
        for resource in ("release_pages", "tag_pages"):
            for value in ({}, {"message": "Not Found"}, [dict(id=1)], "[]", [[None]]):
                with self.subTest(resource=resource, value=value):
                    self.release_pages, self.tag_pages = [[]], [[]]
                    setattr(self, resource, value)
                    with self.assertRaisesRegex(resolver.ReleaseVersionError, "paginated|non-object"):
                        self.resolve()

    def test_empty_slurp_response_is_supported(self):
        self.release_pages, self.tag_pages = [], []
        self.assertEqual(self.resolve()["versionCode"], 2)

    def test_last_available_version_code(self):
        self.add_release(code=2_099_999_999)
        self.assertEqual(self.resolve()["versionCode"], 2_100_000_000)

    def test_published_code_exhaustion_is_fatal(self):
        self.add_release(code=2_100_000_000)
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "Next versionCode"):
            self.resolve()

    def test_baseline_code_exhaustion_is_fatal(self):
        self.baseline = "versionName=0.1.0\nversionCode=2100000000\n"
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "Next versionCode"):
            self.resolve()

    def test_bad_baseline_properties(self):
        for baseline in (
            "", "versionName=0.1.0", "versionCode=1",
            "versionName=0.1.0\nversionCode=1\nversionCode=2",
            "versionName=0.1.0\nversionCode=1\nextra=2",
            "versionName:0.1.0\nversionCode=1",
            "versionName=0.01.0\nversionCode=1",
            "versionName=0.1.0-preview.1\nversionCode=1",
            "versionName=0.1.0\nversionCode=0",
            "versionName=0.1.0\nversionCode=01",
            "versionName=0.1.0\nversionCode=true",
            "versionName=0.1.0\nversionCode=1.0",
            "versionName=0.1.0\nversionCode=2100000001",
        ):
            with self.subTest(baseline=baseline):
                self.baseline = baseline
                with self.assertRaises(resolver.ReleaseVersionError):
                    self.resolve()

    def test_missing_baseline_fails_explicitly(self):
        with patch.object(Path, "read_text", side_effect=FileNotFoundError("missing")):
            with self.assertRaisesRegex(resolver.ReleaseVersionError, "Cannot read source baseline"):
                self.resolve()

    def test_source_must_be_full_sha(self):
        for source in ("HEAD", "a" * 7, "z" * 40, SOURCE_SHA + "\n", "--help", "$(id)"):
            with self.subTest(source=source):
                with self.assertRaisesRegex(resolver.ReleaseVersionError, "40-character"):
                    self.resolve(source=source)
        self.command_mock.assert_not_called()

    def test_source_must_equal_checked_out_head(self):
        self.head = OTHER_SHA
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "checked-out HEAD"):
            self.resolve()

    def test_shallow_checkout_rejected(self):
        self.shallow = "true"
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "fetch-depth: 0"):
            self.resolve()

    def test_hexadecimal_input_is_case_insensitive(self):
        self.add_release(sha=OLD_SHA.upper())
        metadata = self.resolve(source=SOURCE_SHA.upper())
        self.assertEqual(metadata["sourceSha"], SOURCE_SHA)

    def test_invalid_repository_inputs_are_never_executed(self):
        for repository in (
            "", "fpink", "owner/repo/extra", "owner/../repo", "owner/.",
            "owner/..", "-owner/repo", "owner/repo\n", "owner/repo;echo x",
        ):
            with self.subTest(repository=repository):
                with self.assertRaisesRegex(resolver.ReleaseVersionError, "OWNER/REPO"):
                    self.resolve(repository=repository)
        self.command_mock.assert_not_called()

    def test_missing_executable_fails_explicitly(self):
        self.command_mock.side_effect = FileNotFoundError("gh not found")
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "Could not run"):
            self.resolve()

    def test_api_failure_does_not_fall_back_to_empty_history(self):
        original = self.run_command

        def fail_api(arguments, **kwargs):
            if arguments[0] == "gh":
                return subprocess.CompletedProcess(arguments, 1, stdout="", stderr="API unavailable")
            return original(arguments, **kwargs)

        self.command_mock.side_effect = fail_api
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "API unavailable"):
            self.resolve()


class DeclaredReleaseTests(ResolverFixture):
    """version.properties declares the release (release-bump PR flow used by F-Droid auto-update)."""

    def publish_previews(self, last=6):
        for number in range(2, last + 1):
            self.add_release(f"0.1.0-preview.{number}", code=number, prerelease=True)

    def declare(self, release_type="prerelease", requested=None, prepare=False):
        return resolver.resolve_version(
            "example/fpink", SOURCE_SHA, requested, release_type=release_type,
            version_file=Path("version.properties"), prepare=prepare,
        )

    def test_declared_preview_matches_next_release(self):
        self.publish_previews()
        self.baseline = "versionName=0.1.0-preview.7\nversionCode=7\n"
        metadata = self.declare()
        self.assertEqual((metadata["versionName"], metadata["versionCode"]), ("0.1.0-preview.7", 7))
        self.assertEqual(metadata["previousTag"], "v0.1.0-preview.6")
        self.assertTrue(metadata["isPrerelease"])

    def test_declared_stable_after_previews(self):
        self.publish_previews()
        self.baseline = "versionName=0.1.0\nversionCode=7\n"
        metadata = self.declare(release_type="stable")
        self.assertEqual((metadata["versionName"], metadata["versionCode"]), ("0.1.0", 7))

    def test_crlf_declaration_is_accepted(self):
        self.publish_previews()
        self.baseline = "versionName=0.1.0-preview.7\r\nversionCode=7\r\n"
        self.assertEqual(self.declare()["versionCode"], 7)

    def test_declared_code_may_skip_but_never_reuse(self):
        self.publish_previews()
        self.baseline = "versionName=0.1.0-preview.7\nversionCode=9\n"
        self.assertEqual(self.declare()["versionCode"], 9)
        for code in (6, 2):
            with self.subTest(code=code):
                self.baseline = f"versionName=0.1.0-preview.7\nversionCode={code}\n"
                with self.assertRaisesRegex(resolver.ReleaseVersionError, "greater than every published"):
                    self.declare()

    def test_stale_declaration_collides_with_published_tag(self):
        self.publish_previews()
        self.baseline = "versionName=0.1.0-preview.6\nversionCode=7\n"
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "collides|greater than"):
            self.declare()

    def test_requested_version_must_equal_declaration(self):
        self.publish_previews()
        self.baseline = "versionName=0.1.0-preview.7\nversionCode=7\n"
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "release-bump PR"):
            self.declare(requested="0.1.0-preview.8")
        self.assertEqual(self.declare(requested="0.1.0-preview.7")["versionCode"], 7)

    def test_release_type_must_match_declaration(self):
        self.baseline = "versionName=0.1.0-preview.7\nversionCode=7\n"
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "does not match release type"):
            self.declare(release_type="stable")
        self.command_mock.assert_not_called()

    def test_invalid_declarations_fail_before_network(self):
        for baseline in ("versionName=0.1.0-preview.07\nversionCode=7\n", "versionName=0.1.0\nversionCode=0\n", ""):
            with self.subTest(baseline=baseline):
                self.baseline = baseline
                with self.assertRaises(resolver.ReleaseVersionError):
                    self.declare(release_type="stable")
        self.command_mock.assert_not_called()

    def test_prepare_from_legacy_baseline(self):
        self.publish_previews()
        metadata = self.declare(prepare=True)
        self.assertEqual((metadata["versionName"], metadata["versionCode"]), ("0.1.0-preview.7", 7))

    def test_prepare_after_published_declaration(self):
        self.publish_previews(7)
        self.baseline = "versionName=0.1.0-preview.7\nversionCode=7\n"
        metadata = self.declare(prepare=True)
        self.assertEqual((metadata["versionName"], metadata["versionCode"]), ("0.1.0-preview.8", 8))
        stable = self.declare(release_type="stable", prepare=True)
        self.assertEqual((stable["versionName"], stable["versionCode"]), ("0.1.0", 8))

    def test_prepare_is_idempotent_for_an_unpublished_declaration(self):
        self.publish_previews()
        self.baseline = "versionName=0.1.0-preview.7\nversionCode=7\n"
        metadata = self.declare(prepare=True)
        self.assertEqual((metadata["versionName"], metadata["versionCode"]), ("0.1.0-preview.7", 7))

    def test_prepare_requires_a_version_file(self):
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "version.properties path"):
            resolver.resolve_version("example/fpink", SOURCE_SHA, prepare=True)


class ChangelogTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.changelogs = self.root / resolver.CHANGELOG_DIRECTORY
        self.changelogs.mkdir(parents=True)

    def properties(self, text):
        (self.root / "version.properties").write_text(text, encoding="utf-8")

    def test_changelog_requirements(self):
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "requires the changelog"):
            resolver.check_changelog(self.root, 7)
        (self.changelogs / "7.txt").write_text(" \n", encoding="utf-8")
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "is empty"):
            resolver.check_changelog(self.root, 7)
        (self.changelogs / "7.txt").write_text("x" * 501, encoding="utf-8")
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "exceeds 500"):
            resolver.check_changelog(self.root, 7)
        (self.changelogs / "7.txt").write_text("Fixes.\n", encoding="utf-8")
        resolver.check_changelog(self.root, 7)

    def test_offline_check_skips_the_undeclared_baseline(self):
        self.properties("versionName=0.1.0\nversionCode=1\n")
        self.assertEqual(resolver.check_version_properties(self.root)[1], 1)
        self.properties("versionName=0.1.0-preview.7\nversionCode=7\n")
        with self.assertRaisesRegex(resolver.ReleaseVersionError, "requires the changelog"):
            resolver.check_version_properties(self.root)
        (self.changelogs / "7.txt").write_text("Fixes.\n", encoding="utf-8")
        self.assertEqual(str(resolver.check_version_properties(self.root)[0]), "0.1.0-preview.7")

    def test_write_version_properties_creates_stub_once(self):
        metadata = {"versionName": "0.1.0-preview.7", "versionCode": 7}
        path = self.root / "version.properties"
        changelog = resolver.write_version_properties(metadata, path, self.root)
        self.assertEqual(path.read_bytes(), b"versionName=0.1.0-preview.7\nversionCode=7\n")
        self.assertEqual(changelog.read_text(encoding="utf-8"), "")
        changelog.write_text("Kept.\n", encoding="utf-8")
        resolver.write_version_properties(metadata, path, self.root)
        self.assertEqual(changelog.read_text(encoding="utf-8"), "Kept.\n")


class VersionTests(unittest.TestCase):
    def test_semver_precedence(self):
        values = ["1.0.0-1", "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta",
                  "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1"]
        parsed = [resolver.Version.parse(value) for value in values]
        self.assertEqual(sorted(reversed(parsed)), parsed)
        self.assertEqual([str(version) for version in parsed], values)

    def test_invalid_prerelease_identifiers(self):
        for value in ("0.1.0-preview.01", "0.1.0-", "0.1.0-rc..1", "0.1.0-rc_1",
                      "0.1.0-rc.1+build", "0.1.0-rc.1\n", "0.1.0-rc.１"):
            with self.subTest(value=value), self.assertRaises(resolver.ReleaseVersionError):
                resolver.Version.parse(value)


class OutputTests(unittest.TestCase):
    def setUp(self):
        self.metadata = {
            "schemaVersion": 1,
            "applicationId": "com.fpink.capture",
            "versionName": "0.1.0",
            "versionCode": 2,
            "tag": "v0.1.0",
            "sourceSha": SOURCE_SHA,
            "previousTag": None,
            "previousSigningCertificateSha256": None,
            "isPrerelease": False,
        }

    def test_json_and_github_output_and_summary(self):
        output = mock_open()
        summary = mock_open()
        handles = {"workflow-output": output, "workflow-summary": summary}

        def open_handle(path, *args, **kwargs):
            return handles[path](path, *args, **kwargs)

        with patch.object(resolver.os, "environ", {
            "GITHUB_OUTPUT": "workflow-output",
            "GITHUB_STEP_SUMMARY": "workflow-summary",
        }), patch.object(Path, "mkdir") as mkdir, \
                patch.object(Path, "write_text") as write_text, \
                patch("builtins.open", side_effect=open_handle):
            resolver.write_outputs(self.metadata, Path("resolved.json"))
        mkdir.assert_called_once_with(parents=True, exist_ok=True)
        self.assertEqual(json.loads(write_text.call_args.args[0]), self.metadata)
        self.assertEqual(write_text.call_args.kwargs, {"encoding": "utf-8"})
        self.assertEqual(
            "".join(call.args[0] for call in output().write.call_args_list),
            f"version_name=0.1.0\nversion_code=2\ntag=v0.1.0\n"
            f"source_sha={SOURCE_SHA}\nprevious_tag=\nprerelease=false\n",
        )
        summary_text = "".join(call.args[0] for call in summary().write.call_args_list)
        self.assertIn("`0.1.0`", summary_text)
        self.assertIn(SOURCE_SHA, summary_text)
        self.assertIn("`none`", summary_text)
        output.assert_any_call("workflow-output", "a", encoding="utf-8", newline="\n")

    def test_previous_tag_output(self):
        self.metadata["previousTag"] = "v0.0.9"
        opened = mock_open()
        with patch.object(resolver.os, "environ", {"GITHUB_OUTPUT": "workflow-output"}), \
                patch.object(Path, "mkdir"), patch.object(Path, "write_text"), \
                patch("builtins.open", opened):
            resolver.write_outputs(self.metadata, Path("resolved.json"))
        self.assertIn(
            "previous_tag=v0.0.9\n",
            "".join(call.args[0] for call in opened().write.call_args_list),
        )

    def test_workflow_environment_is_optional(self):
        with patch.object(resolver.os, "environ", {}), \
                patch.object(Path, "mkdir"), patch.object(Path, "write_text"), \
                patch("builtins.open") as opened:
            resolver.write_outputs(self.metadata, Path("resolved.json"))
        opened.assert_not_called()

    def test_cli_contract(self):
        arguments = [
            "--repository", "example/fpink", "--source-sha", SOURCE_SHA,
            "--requested-version", "0.1.0", "--output", "resolved.json",
        ]
        with patch.object(resolver, "resolve_version", return_value=self.metadata) as resolve, \
                patch.object(resolver, "write_outputs") as write, \
                patch.object(resolver.sys, "stdout", new_callable=io.StringIO):
            self.assertEqual(resolver.main(arguments), 0)
        resolve.assert_called_once_with("example/fpink", SOURCE_SHA, "0.1.0", release_type="stable")
        write.assert_called_once_with(self.metadata, Path("resolved.json"))

    def test_cli_prerelease_contract(self):
        with patch.object(resolver, "resolve_version", return_value=self.metadata) as resolve, \
                patch.object(resolver, "write_outputs"), \
                patch.object(resolver.sys, "stdout", new_callable=io.StringIO):
            self.assertEqual(resolver.main([
                "--repository", "example/fpink", "--source-sha", SOURCE_SHA,
                "--requested-version", "", "--release-type", "prerelease",
                "--output", "resolved.json",
            ]), 0)
        resolve.assert_called_once_with("example/fpink", SOURCE_SHA, "", release_type="prerelease")

    def test_cli_declared_release_requires_its_changelog(self):
        arguments = [
            "--repository", "example/fpink", "--source-sha", SOURCE_SHA, "--release-type", "prerelease",
            "--version-properties", "version.properties", "--output", "resolved.json",
        ]
        with patch.object(resolver, "resolve_version", return_value=self.metadata) as resolve, \
                patch.object(resolver, "check_changelog") as changelog, \
                patch.object(resolver, "write_outputs") as write, \
                patch.object(resolver.sys, "stdout", new_callable=io.StringIO):
            self.assertEqual(resolver.main(arguments), 0)
        resolve.assert_called_once_with(
            "example/fpink", SOURCE_SHA, None, release_type="prerelease",
            version_file=Path("version.properties"), prepare=False,
        )
        changelog.assert_called_once_with(resolver.ROOT, 2)
        write.assert_called_once()
        with patch.object(resolver, "resolve_version", return_value=self.metadata), \
                patch.object(resolver, "check_changelog", side_effect=resolver.ReleaseVersionError("no changelog")), \
                patch.object(resolver, "write_outputs") as write, \
                patch.object(resolver.sys, "stderr", new_callable=io.StringIO):
            self.assertEqual(resolver.main(arguments), 1)
        write.assert_not_called()

    def test_cli_prepare_writes_version_properties_from_head(self):
        head = subprocess.CompletedProcess([], 0, stdout=SOURCE_SHA + "\n", stderr="")
        with patch.object(resolver, "run_command", return_value=head), \
                patch.object(resolver, "resolve_version", return_value=self.metadata) as resolve, \
                patch.object(resolver, "write_version_properties",
                             return_value=resolver.ROOT / "changelog.txt") as write_properties, \
                patch.object(resolver, "write_outputs") as write, \
                patch.object(resolver.sys, "stdout", new_callable=io.StringIO) as stdout:
            self.assertEqual(resolver.main([
                "--repository", "example/fpink", "--release-type", "prerelease", "--prepare",
            ]), 0)
        resolve.assert_called_once_with(
            "example/fpink", SOURCE_SHA, None, release_type="prerelease",
            version_file=resolver.ROOT / "version.properties", prepare=True,
        )
        write_properties.assert_called_once_with(self.metadata, resolver.ROOT / "version.properties")
        write.assert_not_called()
        self.assertIn("Release 0.1.0", stdout.getvalue())

    def test_cli_requires_output_outside_prepare(self):
        with patch.object(resolver.sys, "stderr", new_callable=io.StringIO), \
                self.assertRaises(SystemExit):
            resolver.main(["--repository", "example/fpink", "--source-sha", SOURCE_SHA])

    def test_cli_failure_never_writes_metadata(self):
        with patch.object(resolver, "resolve_version", side_effect=resolver.ReleaseVersionError("bad history")), \
                patch.object(resolver, "write_outputs") as write, \
                patch.object(resolver.sys, "stderr", new_callable=io.StringIO) as stderr:
            self.assertEqual(resolver.main([
                "--repository", "example/fpink", "--source-sha", SOURCE_SHA,
                "--output", "resolved.json",
            ]), 1)
        write.assert_not_called()
        self.assertIn("Release version resolution failed: bad history", stderr.getvalue())

    def test_cli_output_io_failure_is_explicit(self):
        with patch.object(resolver, "resolve_version", return_value=self.metadata), \
                patch.object(resolver, "write_outputs", side_effect=OSError("read-only")), \
                patch.object(resolver.sys, "stderr", new_callable=io.StringIO) as stderr:
            self.assertEqual(resolver.main([
                "--repository", "example/fpink", "--source-sha", SOURCE_SHA,
                "--output", "resolved.json",
            ]), 1)
        self.assertIn("read-only", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()

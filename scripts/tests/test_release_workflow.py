import base64
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest.mock import patch


WORKFLOW = (Path(__file__).resolve().parents[2] / ".github" / "workflows" / "release.yml").read_text()
SCRIPTS = re.findall(r"          python3 -I - <<'PY'\n(.*?)          PY", WORKFLOW, re.DOTALL)
SIGN, PUBLISH = [compile("\n".join(line.removeprefix("          ") for line in source.splitlines()),
                         "release.yml", "exec") for source in SCRIPTS]
CERTIFICATE = "b" * 64
SHA = "a" * 40


class WorkflowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.previous_cwd = Path.cwd()
        os.chdir(self.root)
        self.addCleanup(os.chdir, self.previous_cwd)
        self.env = {
            "VERSION_NAME": "0.1.0", "VERSION_CODE": "2", "TAG": "v0.1.0",
            "PRERELEASE": "false",
            "PREVIOUS_TAG": "", "GITHUB_SHA": SHA, "GITHUB_REPOSITORY": "owner/repo",
            "RUNNER_TEMP": str(self.root), "ANDROID_HOME": str(self.root / "sdk"),
            "GITHUB_STEP_SUMMARY": str(self.root / "summary.txt"),
            "KEYSTORE_BASE64": base64.b64encode(b"test-only-keystore").decode(),
            "KEYSTORE_PASSWORD": "fixture", "KEY_PASSWORD": "fixture", "KEY_ALIAS": "fixture",
            "EXPECTED_CERTIFICATE": CERTIFICATE,
        }
        self.environment = patch.dict(os.environ, self.env)
        self.environment.start()
        self.addCleanup(self.environment.stop)
        incoming = self.root / "incoming"
        incoming.mkdir()
        (incoming / "unsigned.apk").write_bytes(b"unsigned fixture")
        (incoming / "LICENSE").write_text("license fixture")
        self.metadata = {
            "schemaVersion": 1, "applicationId": "com.fpink.capture",
            "versionName": "0.1.0", "versionCode": 2, "tag": "v0.1.0", "sourceSha": SHA,
            "previousSigningCertificateSha256": None,
            "isPrerelease": False,
        }
        self.save_metadata()
        self.calls = []
        self.tags = []
        self.releases = []
        self.tamper_download = False
        self.remote_prerelease = False

    def select_preview(self):
        version = "0.1.0-preview.2"
        os.environ.update(VERSION_NAME=version, TAG="v" + version, PRERELEASE="true")
        self.metadata.update(versionName=version, tag="v" + version, isPrerelease=True)
        self.remote_prerelease = True
        self.save_metadata()

    def save_metadata(self):
        Path("incoming/release-metadata.json").write_text(json.dumps(self.metadata))

    def signing_run(self, args, **kwargs):
        self.calls.append(args)
        if "sign" in args:
            Path(args[args.index("--out") + 1]).write_bytes(b"signed fixture")
        return subprocess.CompletedProcess(args, 0)

    def signing_output(self, args, **kwargs):
        self.calls.append(args)
        if "verify" in args:
            return f"Signer #1 certificate SHA-256 digest: {CERTIFICATE}\n"
        return f"package: name='com.fpink.capture' versionCode='2' versionName='{self.metadata['versionName']}'\n"

    def sign(self):
        with patch("subprocess.run", side_effect=self.signing_run), \
                patch("subprocess.check_output", side_effect=self.signing_output):
            exec(SIGN, {})

    def github_output(self, args, **kwargs):
        self.calls.append(args)
        if args[1:2] == ["api"] and "--paginate" in args:
            return json.dumps([self.tags if "/tags?" in args[-1] else self.releases])
        if args[1:3] == ["release", "view"]:
            return json.dumps({"isDraft": True, "isPrerelease": self.remote_prerelease,
                               "assets": [{"name": p.name} for p in Path("release-assets").iterdir()]})
        if args[1:3] == ["release", "download"]:
            directory = Path(args[args.index("--dir") + 1])
            for p in Path("release-assets").iterdir():
                (directory / p.name).write_bytes(b"tampered" if self.tamper_download else p.read_bytes())
        return "{}"

    def publish(self):
        with patch("subprocess.check_output", side_effect=self.github_output):
            exec(PUBLISH, {})

    def test_manual_only_and_read_only_build(self):
        triggers = WORKFLOW.split("\non:\n", 1)[1].split("\npermissions:", 1)[0]
        self.assertIn("workflow_dispatch:", triggers)
        for event in ("push:", "pull_request:", "schedule:", "workflow_run:"):
            self.assertNotIn(event, triggers)
        self.assertIn("default: false", triggers)
        self.assertIn("default: prerelease", triggers)
        self.assertIn("- stable", triggers)
        self.assertIn('RELEASE_TYPE: ${{ inputs.release_type }}', WORKFLOW)
        self.assertIn('--release-type "$RELEASE_TYPE"', WORKFLOW)
        build, publish = WORKFLOW.split("\n  publish:\n")
        self.assertNotIn("contents: write", build)
        self.assertNotIn("actions/checkout@", publish)
        self.assertIn("persist-credentials: false", build)
        self.assertIn('if [[ "$GITHUB_REF" != refs/heads/main ]]', build)
        self.assertIn("environment: release", publish)
        self.assertIn("RELEASE_PUBLICATION_APPROVED", publish)

    def test_sign_manifest_and_temporary_key_cleanup(self):
        self.sign()
        manifest = json.loads(Path("release-assets/release-manifest.json").read_text())
        self.assertEqual(manifest["versionCode"], 2)
        self.assertEqual(manifest["signingCertificateSha256"], CERTIFICATE)
        self.assertEqual(list(self.root.glob("fpink-signing-*")), [])
        self.assertEqual(len(Path("release-assets/SHA256SUMS").read_text().splitlines()), 3)

    def test_missing_signing_configuration_fails(self):
        os.environ["KEYSTORE_PASSWORD"] = ""
        with self.assertRaisesRegex(RuntimeError, "Missing signing configuration"):
            self.sign()
        self.assertEqual(self.calls, [])

    def test_historical_signer_change_fails_before_signing(self):
        self.metadata["previousSigningCertificateSha256"] = "c" * 64
        self.save_metadata()
        with self.assertRaisesRegex(RuntimeError, "rotation"):
            self.sign()
        self.assertEqual(self.calls, [])

    def test_actual_signer_mismatch(self):
        os.environ["EXPECTED_CERTIFICATE"] = "c" * 64
        with self.assertRaisesRegex(RuntimeError, "certificate mismatch"):
            self.sign()
        self.assertEqual(list(self.root.glob("fpink-signing-*")), [])

    def test_signing_failure_cleans_key(self):
        with patch("subprocess.run", side_effect=subprocess.CalledProcessError(1, "apksigner")), \
                self.assertRaises(subprocess.CalledProcessError):
            exec(SIGN, {})
        self.assertEqual(list(self.root.glob("fpink-signing-*")), [])

    def test_source_mismatch(self):
        self.metadata["sourceSha"] = "c" * 40
        self.save_metadata()
        with self.assertRaisesRegex(RuntimeError, "metadata mismatch"):
            self.sign()

    def test_publish_only_after_asset_download_verification(self):
        self.sign()
        self.calls.clear()
        self.publish()
        verbs = [args[1:3] for args in self.calls]
        self.assertLess(verbs.index(["release", "download"]), verbs.index(["release", "edit"]))
        self.assertIn("--draft", next(args for args in self.calls if args[1:3] == ["release", "create"]))
        self.assertIn("--draft=false", self.calls[-1])
        self.assertIn("--prerelease=false", self.calls[-1])
        self.assertIn("--latest=true", self.calls[-1])

    def test_preview_is_signed_and_published_with_prerelease_flag(self):
        self.select_preview()
        self.sign()
        manifest = json.loads(Path("release-assets/release-manifest.json").read_text())
        self.assertEqual(manifest["versionName"], "0.1.0-preview.2")
        self.assertEqual(manifest["apk"], "FPInk-0.1.0-preview.2.apk")
        self.assertNotIn("isPrerelease", manifest)
        self.calls.clear()
        self.publish()
        create = next(args for args in self.calls if args[1:3] == ["release", "create"])
        self.assertIn("--prerelease", create)
        self.assertIn("--latest=false", create)
        self.assertIn("--prerelease=true", self.calls[-1])
        self.assertIn("--latest=false", self.calls[-1])
        self.assertIn("Pre-release: not production-ready.", Path("release-notes.txt").read_text())

    def test_preview_draft_cannot_be_published_with_wrong_label(self):
        self.select_preview()
        self.sign()
        self.calls.clear()
        self.remote_prerelease = False
        with self.assertRaisesRegex(RuntimeError, "Draft type"):
            self.publish()
        self.assertFalse(any(args[1:3] == ["release", "edit"] for args in self.calls))

    def test_signing_rejects_release_type_mismatches(self):
        self.select_preview()
        self.metadata["isPrerelease"] = False
        self.save_metadata()
        with self.assertRaisesRegex(RuntimeError, "metadata type mismatch"):
            self.sign()
        self.assertEqual(self.calls, [])

    def test_signing_rejects_prerelease_leading_zeros(self):
        self.select_preview()
        os.environ["VERSION_NAME"] = "0.1.0-preview.02"
        with self.assertRaisesRegex(RuntimeError, "Invalid prerelease identifier"):
            self.sign()

    def test_signing_rejects_unrecognized_release_type(self):
        os.environ["PRERELEASE"] = "yes"
        with self.assertRaisesRegex(RuntimeError, "Invalid release type"):
            self.sign()

    def test_collision_never_writes(self):
        for tags, releases in ([{"name": "v0.1.0"}], []), ([], [{"tag_name": "v0.1.0"}]):
            with self.subTest(tags=tags, releases=releases):
                self.tags, self.releases, self.calls = tags, releases, []
                with self.assertRaisesRegex(RuntimeError, "already exists"):
                    self.publish()
                self.assertTrue(all("--paginate" in args for args in self.calls))

    def test_bad_uploaded_bytes_leave_draft_unpublished(self):
        self.sign()
        self.calls.clear()
        self.tamper_download = True
        with self.assertRaisesRegex(RuntimeError, "checksum mismatch"):
            self.publish()
        self.assertFalse(any(args[1:3] == ["release", "edit"] for args in self.calls))


if __name__ == "__main__":
    unittest.main()

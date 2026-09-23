import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "recognition" / "paddle" / "scripts" / "verify-reproducibility.py"
MANIFEST = ROOT / "recognition" / "paddle" / "reproducibility.lock.json"

spec = importlib.util.spec_from_file_location("verify_ocr_reproducibility", SCRIPT)
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class ReproducibilityPolicyTests(unittest.TestCase):
    def setUp(self):
        self.manifest = verifier.read_manifest(MANIFEST)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def write_outputs(self, directory: Path, suffix: bytes):
        directory.mkdir()
        (directory / self.manifest["runtime"]["expectedFile"]).write_bytes(b"runtime" + suffix)
        for model in self.manifest["models"]:
            (directory / model["output"]["file"]).write_bytes(model["name"].encode() + suffix)

    def test_checked_in_manifest_requires_source_reproduction(self):
        self.assertTrue(self.manifest["policy"]["requireByteIdenticalBuilds"])
        self.assertFalse(self.manifest["policy"]["allowPrebuiltFallback"])
        self.assertTrue(all(
            model["conversion"]["status"] == "requires-reproduction"
            for model in self.manifest["models"]
        ))

    def test_byte_identical_outputs_pass(self):
        first = self.root / "first"
        second = self.root / "second"
        self.write_outputs(first, b"same")
        self.write_outputs(second, b"same")

        verifier.verify_outputs(self.manifest, first)
        verifier.verify_outputs(self.manifest, second)
        verifier.compare_outputs(self.manifest, first, second)

    def test_different_outputs_fail(self):
        first = self.root / "first"
        second = self.root / "second"
        self.write_outputs(first, b"first")
        self.write_outputs(second, b"second")

        with self.assertRaisesRegex(ValueError, "Non-reproducible output"):
            verifier.compare_outputs(self.manifest, first, second)

    def test_prebuilt_fallback_manifest_is_rejected(self):
        invalid = json.loads(MANIFEST.read_text(encoding="utf-8"))
        invalid["policy"]["allowPrebuiltFallback"] = True
        invalid_path = self.root / "invalid.lock.json"
        invalid_path.write_text(json.dumps(invalid), encoding="utf-8")

        with self.assertRaisesRegex(ValueError, "prebuilt fallback"):
            verifier.read_manifest(invalid_path)

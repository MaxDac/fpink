import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / "recognition" / "paddle"
SCRIPT = MODULE / "scripts" / "verify-model-provenance.py"
MANIFEST = MODULE / "models-provenance.lock.json"

spec = importlib.util.spec_from_file_location("verify_model_provenance", SCRIPT)
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class ManifestTests(unittest.TestCase):
    def setUp(self):
        self.raw = json.loads(MANIFEST.read_text(encoding="utf-8"))
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def read(self, manifest):
        path = self.root / "models-provenance.lock.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        return verifier.read_manifest(path)

    def test_checked_in_lock_matches_artifacts(self):
        manifest = verifier.read_manifest(MANIFEST)
        verifier.verify_artifacts(manifest, MODULE, check_files=False)

    def test_regeneration_claim_is_rejected(self):
        invalid = copy.deepcopy(self.raw)
        invalid["models"][0]["regeneration"]["claimed"] = True
        with self.assertRaisesRegex(ValueError, "regeneration"):
            self.read(invalid)

    def test_unpinned_checkpoint_is_rejected(self):
        invalid = copy.deepcopy(self.raw)
        invalid["models"][1]["upstreamCheckpoint"]["revision"] = "main"
        with self.assertRaisesRegex(ValueError, "full revision"):
            self.read(invalid)

    def test_asset_drift_from_artifacts_lock_is_rejected(self):
        invalid = copy.deepcopy(self.raw)
        invalid["models"][0]["asset"]["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "differs"):
            verifier.verify_artifacts(self.read(invalid), MODULE, check_files=False)


class DictionaryTests(unittest.TestCase):
    ENTRIES = ["a", "1", " ", "\u00e9"]

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.module = Path(self.temp.name)
        self.source = (
            "PostProcess:\n  name: CTCLabelDecode\n  character_dict:\n"
            "  - a\n  - 1\n  - ' '\n  - \u00e9\n"
        ).encode("utf-8")
        self.manifest = {"dictionary": {
            "asset": {"file": "dict.txt"},
            "derivation": {"file": "inference.yml", "field": "PostProcess.character_dict",
                           "bytes": len(self.source), "sha256": hashlib.sha256(self.source).hexdigest()},
        }}

    def write_dictionary(self, text):
        (self.module / "dict.txt").write_bytes(text.encode("utf-8"))

    def test_derived_dictionary_passes(self):
        self.write_dictionary("\n".join(self.ENTRIES))
        verifier.verify_dictionary(self.manifest, self.module, self.source)

    def test_mismatched_dictionary_fails(self):
        for text in ("\n".join(self.ENTRIES[:-1]), "\n".join(self.ENTRIES) + "\n",
                     "\r\n".join(self.ENTRIES), "\n".join(reversed(self.ENTRIES))):
            with self.subTest(text=text):
                self.write_dictionary(text)
                with self.assertRaises(ValueError):
                    verifier.verify_dictionary(self.manifest, self.module, self.source)

    def test_tampered_source_fails(self):
        self.write_dictionary("\n".join(self.ENTRIES))
        with self.assertRaisesRegex(ValueError, "pinned"):
            verifier.verify_dictionary(self.manifest, self.module, self.source + b"\n")


if __name__ == "__main__":
    unittest.main()

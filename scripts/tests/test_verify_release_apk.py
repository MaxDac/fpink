import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import warnings
import zipfile


spec = importlib.util.spec_from_file_location(
    "verify_release_apk", Path(__file__).resolve().parents[1] / "verify_release_apk.py"
)
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class IdentityTests(unittest.TestCase):
    BADGING = "package: name='com.fpink.capture' versionCode='2' versionName='0.1.0' platformBuildVersionName='36'\n"

    def test_release(self):
        verifier.verify_identity(self.BADGING, "0.1.0", 2)

    def test_wrong_identity(self):
        for badging in ("", self.BADGING.replace("com.fpink.capture", "com.example"),
                        self.BADGING.replace("versionCode='2'", "versionCode='3'"),
                        self.BADGING.replace("0.1.0", "0.2.0")):
            with self.subTest(badging=badging), self.assertRaises(ValueError):
                verifier.verify_identity(badging, "0.1.0", 2)

    def test_debuggable(self):
        with self.assertRaisesRegex(ValueError, "debuggable"):
            verifier.verify_identity(self.BADGING + "application-debuggable\n", "0.1.0", 2)


class AssetTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk = self.root / "test.apk"
        models = self.root / "recognition" / "models"
        models.mkdir(parents=True)
        self.model = b"pinned-model"
        self.notice = b"notice"
        (models / "artifacts.lock.json").write_text(json.dumps({"packagedFiles": [
            {"destination": "src/main/assets/recognition/model.onnx",
             "bytes": len(self.model), "sha256": hashlib.sha256(self.model).hexdigest()},
            {"destination": "src/main/assets/recognition/NOTICE.txt",
             "bytes": len(self.notice), "sha256": hashlib.sha256(self.notice).hexdigest()},
        ]}))
        self.entries = {
            "assets/recognition/model.onnx": self.model,
            "assets/recognition/NOTICE.txt": self.notice,
            "lib/arm64-v8a/libonnxruntime.so": b"ort",
            "lib/arm64-v8a/libonnxruntime4j_jni.so": b"jni",
        }

    def write_apk(self):
        with zipfile.ZipFile(self.apk, "w") as archive:
            for name, data in self.entries.items():
                archive.writestr(name, data)

    def test_valid(self):
        self.write_apk()
        verifier.verify_assets(self.apk, self.root)

    def test_tampered_or_missing_pinned_asset(self):
        for name in ("assets/recognition/model.onnx", "assets/recognition/NOTICE.txt"):
            with self.subTest(name=name):
                original = self.entries[name]
                self.entries[name] = b"tampered"
                self.write_apk()
                with self.assertRaisesRegex(ValueError, "pinned"):
                    verifier.verify_assets(self.apk, self.root)
                del self.entries[name]
                self.write_apk()
                with self.assertRaises(KeyError):
                    verifier.verify_assets(self.apk, self.root)
                self.entries[name] = original

    def test_unpinned_recognition_asset(self):
        self.entries["assets/recognition/extra.onnx"] = b"extra"
        self.write_apk()
        with self.assertRaisesRegex(ValueError, "Unpinned"):
            verifier.verify_assets(self.apk, self.root)

    def test_missing_or_empty_runtime(self):
        self.entries["lib/arm64-v8a/libonnxruntime.so"] = b""
        self.write_apk()
        with self.assertRaisesRegex(ValueError, "Empty"):
            verifier.verify_assets(self.apk, self.root)
        self.entries["lib/arm64-v8a/libonnxruntime.so"] = b"ort"
        del self.entries["lib/arm64-v8a/libonnxruntime4j_jni.so"]
        self.write_apk()
        with self.assertRaises(KeyError):
            verifier.verify_assets(self.apk, self.root)

    def test_duplicate_entry(self):
        self.write_apk()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.apk, "a") as archive:
                archive.writestr("assets/recognition/model.onnx", self.model)
        with self.assertRaisesRegex(ValueError, "duplicate"):
            verifier.verify_assets(self.apk, self.root)

    def test_repository_lock_file_is_consistent(self):
        lock = json.loads((verifier.ROOT / "recognition" / "models" / "artifacts.lock.json").read_text())
        for item in lock["packagedFiles"]:
            path = verifier.ROOT / "recognition" / "models" / item["destination"]
            self.assertEqual(path.stat().st_size, item["bytes"], item["destination"])

if __name__ == "__main__":
    unittest.main()

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
        paddle = self.root / "recognition" / "paddle"
        paddle.mkdir(parents=True)
        self.model = b"pinned-model"
        pin = {"bytes": len(self.model), "sha256": hashlib.sha256(self.model).hexdigest()}
        (paddle / "artifacts.lock.json").write_text(json.dumps({"archives": [{"files": [
            {"destination": "src/main/assets/paddle/model.nb", **pin},
            {"destination": "native/arm64-v8a/libpaddle_light_api_shared.so",
             "bytes": 999, "sha256": "old", "normalization": pin},
        ]}]}))
        (paddle / "licenses.lock.json").write_text(json.dumps([{"name": "LICENSE", **pin}]))
        self.entries = {
            "assets/paddle/model.nb": self.model,
            "lib/arm64-v8a/libpaddle_light_api_shared.so": self.model,
            "assets/paddle/licenses/LICENSE": self.model,
            "assets/paddle/NOTICE.txt": b"notice",
            "lib/arm64-v8a/libfpink_paddle.so": b"wrapper",
            "lib/arm64-v8a/libc++_shared.so": b"cxx",
        }

    def write_apk(self):
        with zipfile.ZipFile(self.apk, "w") as archive:
            for name, data in self.entries.items():
                archive.writestr(name, data)

    def test_valid_including_normalized_runtime(self):
        self.write_apk()
        verifier.verify_assets(self.apk, self.root)

    def test_tampered_model_or_license(self):
        for name in ("assets/paddle/model.nb", "assets/paddle/licenses/LICENSE"):
            with self.subTest(name=name):
                original = self.entries[name]
                self.entries[name] = b"tampered"
                self.write_apk()
                with self.assertRaisesRegex(ValueError, "pinned"):
                    verifier.verify_assets(self.apk, self.root)
                self.entries[name] = original

    def test_missing_runtime(self):
        del self.entries["lib/arm64-v8a/libpaddle_light_api_shared.so"]
        self.write_apk()
        with self.assertRaises(KeyError):
            verifier.verify_assets(self.apk, self.root)

    def test_empty_notice(self):
        self.entries["assets/paddle/NOTICE.txt"] = b""
        self.write_apk()
        with self.assertRaisesRegex(ValueError, "Empty"):
            verifier.verify_assets(self.apk, self.root)

    def test_source_built_runtime(self):
        self.entries["lib/arm64-v8a/libpaddle_light_api_shared.so"] = b"source-built"
        self.write_apk()
        with self.assertRaisesRegex(ValueError, "pinned"):
            verifier.verify_assets(self.apk, self.root)
        verifier.verify_assets(self.apk, self.root, hashlib.sha256(b"source-built").hexdigest())
        with self.assertRaisesRegex(ValueError, "source-built"):
            verifier.verify_assets(self.apk, self.root, hashlib.sha256(b"other").hexdigest())

    def test_source_runtime_digest(self):
        sums = self.root / "SHA256SUMS"
        sums.write_text("aa  src/main/cpp/third_party/paddle_lite/paddle_api.h\n"
                        "bb  native/arm64-v8a/libpaddle_light_api_shared.so\n")
        self.assertEqual(verifier.source_runtime_digest(sums), "bb")
        sums.write_text("aa  other\n")
        with self.assertRaises(ValueError):
            verifier.source_runtime_digest(sums)

    def test_duplicate_entry(self):
        self.write_apk()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.apk, "a") as archive:
                archive.writestr("assets/paddle/model.nb", self.model)
        with self.assertRaisesRegex(ValueError, "duplicate"):
            verifier.verify_assets(self.apk, self.root)


if __name__ == "__main__":
    unittest.main()

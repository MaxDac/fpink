"""Verify the unsigned release APK before it is passed to the signing job."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile


ROOT = Path(__file__).resolve().parents[1]


def verify_identity(badging, version_name, version_code):
    package = re.search(
        r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",
        badging,
        re.MULTILINE,
    )
    if not package or package.groups() != (
        "com.fpink.capture", str(version_code), version_name
    ):
        raise ValueError("APK application ID or version does not match the release.")
    if re.search(r"^application-debuggable(?:\s|$)", badging, re.MULTILINE):
        raise ValueError("A debuggable APK cannot be released.")


NATIVE_LIBRARIES = ("lib/arm64-v8a/libonnxruntime.so", "lib/arm64-v8a/libonnxruntime4j_jni.so")


def verify_assets(apk, root=ROOT):
    models = json.loads((root / "recognition" / "models" / "artifacts.lock.json").read_text())
    expected = {
        item["destination"].removeprefix("src/main/"): item
        for item in models["packagedFiles"]
    }
    with zipfile.ZipFile(apk) as archive:
        if len(archive.namelist()) != len(set(archive.namelist())):
            raise ValueError("APK contains duplicate ZIP entries.")
        for name, item in expected.items():
            data = archive.read(name)
            if len(data) != item["bytes"] or hashlib.sha256(data).hexdigest() != item["sha256"]:
                raise ValueError(f"Packaged artifact does not match its pinned content: {name}")
        unpinned = sorted(
            name for name in archive.namelist()
            if name.startswith("assets/recognition/") and name not in expected
        )
        if unpinned:
            raise ValueError(f"Unpinned recognition assets in APK: {unpinned}")
        for name in NATIVE_LIBRARIES:
            if not archive.read(name):
                raise ValueError(f"Empty required APK entry: {name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    args = parser.parse_args()
    suffix = ".exe" if os.name == "nt" else ""
    badging = subprocess.check_output(
        [str(args.build_tools / ("aapt2" + suffix)), "dump", "badging", str(args.apk)],
        text=True,
        encoding="utf-8",
    )
    verify_identity(badging, args.version_name, args.version_code)
    verify_assets(args.apk)
    subprocess.run(
        [str(args.build_tools / ("zipalign" + suffix)), "-c", "-P", "16", "4", str(args.apk)],
        check=True,
    )
    print("Release APK identity, pinned assets, notices and native alignment verified.")


if __name__ == "__main__":
    main()

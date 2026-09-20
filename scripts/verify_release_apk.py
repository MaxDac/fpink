"""Verify the unsigned release APK before it is passed to the signing job."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
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


def verify_assets(apk, root=ROOT):
    paddle = root / "recognition" / "paddle"
    artifacts = json.loads((paddle / "artifacts.lock.json").read_text())
    licenses = json.loads((paddle / "licenses.lock.json").read_text())
    expected = {}
    for archive in artifacts["archives"]:
        for item in archive["files"]:
            destination = item["destination"]
            if destination.startswith("src/main/assets/"):
                entry = destination.removeprefix("src/main/")
            elif destination.startswith("native/"):
                entry = "lib/" + destination.removeprefix("native/")
            else:
                continue
            expected[entry] = item.get("normalization", item)
    for item in licenses:
        expected["assets/paddle/licenses/" + item["name"]] = item
    with zipfile.ZipFile(apk) as archive:
        if len(archive.namelist()) != len(set(archive.namelist())):
            raise ValueError("APK contains duplicate ZIP entries.")
        for name, item in expected.items():
            data = archive.read(name)
            if len(data) != item["bytes"] or hashlib.sha256(data).hexdigest() != item["sha256"]:
                raise ValueError(f"Packaged artifact does not match its pinned content: {name}")
        for name in (
            "assets/paddle/NOTICE.txt",
            "lib/arm64-v8a/libfpink_paddle.so",
            "lib/arm64-v8a/libc++_shared.so",
        ):
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
    with tempfile.TemporaryDirectory(prefix="fpink-elf-") as directory:
        with zipfile.ZipFile(args.apk) as archive:
            for name in ("libfpink_paddle.so", "libpaddle_light_api_shared.so", "libc++_shared.so"):
                library = Path(directory) / name
                library.write_bytes(archive.read("lib/arm64-v8a/" + name))
                subprocess.run(
                    ["pwsh", "-NoProfile", "-File",
                     str(ROOT / "recognition" / "paddle" / "scripts" / "verify-elf.ps1"),
                     "-Path", str(library)],
                    check=True,
                )
    print("Release APK identity, pinned assets, notices and native alignment verified.")


if __name__ == "__main__":
    main()

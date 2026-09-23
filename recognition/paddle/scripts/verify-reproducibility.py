#!/usr/bin/env python3
"""Fail-closed verification for the OCR source-reproduction evidence."""

import argparse
import hashlib
import json
import sys
from urllib.request import urlopen
from pathlib import Path


def digest(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(65536), b""):
            hasher.update(chunk)
    return hasher.hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def verify_file(path: Path) -> None:
    require(path.is_file(), f"Missing output: {path}")
    require(path.stat().st_size > 0, f"Empty output: {path}")


def read_manifest(path: Path) -> dict:
    with path.open(encoding="utf-8") as stream:
        manifest = json.load(stream)

    require(manifest.get("schema") == 1, "Unsupported reproducibility manifest schema")
    policy = manifest.get("policy")
    require(isinstance(policy, dict) and policy.get("requireByteIdenticalBuilds") is True,
            "Source reproduction must require byte-identical builds")
    require(policy.get("allowPrebuiltFallback") is False,
            "A prebuilt fallback is forbidden by the reproducibility policy")

    runtime = manifest.get("runtime")
    require(isinstance(runtime, dict), "Missing runtime provenance")
    source = runtime.get("source")
    require(isinstance(source, dict) and len(source.get("commit", "")) == 40,
            "Runtime must be pinned to a full source commit")
    archive = runtime.get("thirdPartyArchive")
    require(isinstance(archive, dict) and isinstance(archive.get("bytes"), int) and
            len(archive.get("sha256", "")) == 64,
            "Paddle-Lite third-party archive must be hash pinned")
    require(runtime.get("status") == "requires-reproduction",
            "Runtime provenance status must not claim an unverified source build")

    models = manifest.get("models")
    require(isinstance(models, list) and len(models) == 2,
            "Exactly two PP-OCRv5 models must be declared")
    for model in models:
        require(model.get("conversion", {}).get("status") == "requires-reproduction",
                f"{model.get('name', 'model')} must not claim an unverified conversion")
        output = model.get("output", {})
        require(isinstance(output.get("bytes"), int) and len(output.get("sha256", "")) == 64,
                f"{model.get('name', 'model')} output must be hash pinned")
        source_files = model.get("source", {}).get("files")
        require(isinstance(source_files, list) and source_files,
                f"{model.get('name', 'model')} source files are missing")
        for item in source_files:
            require(isinstance(item.get("bytes"), int) and len(item.get("sha256", "")) == 64,
                    f"{model.get('name', 'model')} source file {item.get('name')} must be hash pinned")
    return manifest


def verify_outputs(manifest: dict, directory: Path) -> None:
    runtime = manifest["runtime"]
    verify_file(directory / runtime["expectedFile"])
    for model in manifest["models"]:
        output = model["output"]
        verify_file(directory / output["file"])


def download_inputs(manifest: dict, directory: Path) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    for model in manifest["models"]:
        source = model["source"]
        model_directory = directory / model["name"]
        model_directory.mkdir(exist_ok=True)
        for item in source["files"]:
            path = model_directory / item["name"]
            url = f"{source['url']}/resolve/{source['revision']}/{item['name']}"
            with urlopen(url) as response, path.open("wb") as stream:
                while chunk := response.read(65536):
                    stream.write(chunk)
            verify_file(path, item["bytes"], item["sha256"])


def compare_outputs(manifest: dict, first: Path, second: Path) -> None:
    names = [manifest["runtime"]["expectedFile"]] + [
        model["output"]["file"] for model in manifest["models"]
    ]
    for name in names:
        first_path = first / name
        second_path = second / name
        require(first_path.is_file() and second_path.is_file(),
                f"Both builds must produce {name}")
        first_hash = digest(first_path)
        second_hash = digest(second_path)
        require(first_hash == second_hash,
                f"Non-reproducible output {name}: {first_hash} != {second_hash}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--first", type=Path)
    parser.add_argument("--second", type=Path)
    parser.add_argument("--download-inputs", type=Path)
    args = parser.parse_args()

    try:
        manifest = read_manifest(args.manifest)
        if (args.first is None) != (args.second is None):
            raise ValueError("--first and --second must be supplied together")
        if args.download_inputs is not None:
            download_inputs(manifest, args.download_inputs)
        if args.first is not None:
            verify_outputs(manifest, args.first)
            verify_outputs(manifest, args.second)
            compare_outputs(manifest, args.first, args.second)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"Reproducibility verification failed: {error}", file=sys.stderr)
        return 1

    print("Reproducibility manifest is valid and all supplied builds are byte-identical.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Fail-closed verification of the PP-OCRv5 model and dictionary provenance lock."""

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from urllib.request import urlopen

MODULE = Path(__file__).resolve().parents[1]
SHA256 = re.compile(r"[0-9a-f]{64}")
COMMIT = re.compile(r"[0-9a-f]{40}")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def require_pin(item, label: str) -> None:
    require(isinstance(item, dict) and isinstance(item.get("bytes"), int) and item["bytes"] > 0
            and isinstance(item.get("sha256"), str) and SHA256.fullmatch(item["sha256"]) is not None,
            f"{label} must be pinned by byte size and SHA-256")


def read_manifest(path: Path) -> dict:
    with path.open(encoding="utf-8") as stream:
        manifest = json.load(stream)
    require(manifest.get("schema") == 2, "Unsupported model provenance lock schema")

    models = manifest.get("models")
    require(isinstance(models, list) and len(models) == 2, "Exactly two PP-OCRv5 models must be declared")
    for model in models:
        name = model.get("name", "model")
        require(model.get("license") == "Apache-2.0", f"{name} must be Apache-2.0")
        require(model.get("status") == "upstream-converted",
                f"{name} must be declared as an upstream-converted asset")
        require(model.get("regeneration", {}).get("claimed") is False,
                f"{name} must not claim a regeneration that is not performed")
        require_pin(model.get("asset"), f"{name} asset")
        require_pin(model.get("distribution"), f"{name} distribution archive")
        checkpoint = model.get("upstreamCheckpoint", {})
        require(COMMIT.fullmatch(checkpoint.get("revision", "")) is not None,
                f"{name} upstream checkpoint must be pinned to a full revision")
        files = checkpoint.get("files")
        require(isinstance(files, list) and files, f"{name} upstream checkpoint files are missing")
        for item in files:
            require_pin(item, f"{name} checkpoint file {item.get('name')}")

    dictionary = manifest.get("dictionary", {})
    require(dictionary.get("status") == "derived-from-upstream",
            "The dictionary must be declared as derived from upstream")
    require_pin(dictionary.get("asset"), "Dictionary asset")
    require_pin(dictionary.get("distribution"), "Dictionary distribution archive")
    derivation = dictionary.get("derivation", {})
    require_pin(derivation, "Dictionary derivation source")
    require(COMMIT.fullmatch(derivation.get("revision", "")) is not None,
            "Dictionary derivation source must be pinned to a full revision")
    require(derivation.get("field") == "PostProcess.character_dict",
            "Dictionary derivation must read PostProcess.character_dict")
    return manifest


def verify_artifacts(manifest: dict, module: Path, check_files: bool) -> None:
    """Every declared asset must be exactly the one pinned and acquired via artifacts.lock.json."""
    artifacts = json.loads((module / "artifacts.lock.json").read_text(encoding="utf-8"))
    pinned = {
        item["destination"]: (archive, item)
        for archive in artifacts["archives"] for item in archive["files"]
    }
    entries = manifest["models"] + [manifest["dictionary"]]
    require(len(pinned) == len(entries), "artifacts.lock.json and the provenance lock list different assets")
    for entry in entries:
        asset, distribution = entry["asset"], entry["distribution"]
        require(asset["file"] in pinned, f"{asset['file']} is not pinned in artifacts.lock.json")
        archive, item = pinned[asset["file"]]
        require((item["bytes"], item["sha256"]) == (asset["bytes"], asset["sha256"]),
                f"{asset['file']} differs between the provenance lock and artifacts.lock.json")
        require((archive["url"], archive["bytes"], archive["sha256"], item["member"]) ==
                (distribution["url"], distribution["bytes"], distribution["sha256"], distribution["member"]),
                f"{asset['file']} distribution differs between the provenance lock and artifacts.lock.json")
        if check_files:
            data = (module / asset["file"]).read_bytes()
            require(len(data) == asset["bytes"] and digest(data) == asset["sha256"],
                    f"{asset['file']} does not match its pinned content")


def derivation_url(manifest: dict) -> str:
    derivation = manifest["dictionary"]["derivation"]
    return f"{derivation['url']}/resolve/{derivation['revision']}/{derivation['file']}"


def dictionary_entries(source: bytes) -> list:
    import yaml  # Only needed for the derivation check.

    document = yaml.safe_load(source)
    entries = document["PostProcess"]["character_dict"]
    require(isinstance(entries, list) and entries, "PostProcess.character_dict is missing or empty")
    return [str(entry) for entry in entries]


def verify_dictionary(manifest: dict, module: Path, source: bytes) -> None:
    derivation = manifest["dictionary"]["derivation"]
    require(len(source) == derivation["bytes"] and digest(source) == derivation["sha256"],
            f"{derivation['file']} does not match its pinned content")
    text = (module / manifest["dictionary"]["asset"]["file"]).read_bytes().decode("utf-8")
    require("\r" not in text, "The dictionary must use LF line endings")
    lines = text.split("\n")
    expected = dictionary_entries(source)
    require(lines == expected,
            f"The dictionary ({len(lines)} entries) is not {derivation['field']} ({len(expected)} entries)")


def fetch(url: str) -> bytes:
    with urlopen(url, timeout=60) as response:
        return response.read()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=MODULE / "models-provenance.lock.json")
    parser.add_argument("--check-files", action="store_true",
                        help="Also hash the checked-in model and dictionary assets")
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--dictionary-source", type=Path,
                        help="Local copy of the pinned inference.yml to derive the dictionary from")
    source.add_argument("--fetch-dictionary-source", action="store_true",
                        help="Download the pinned inference.yml and check the dictionary derivation")
    args = parser.parse_args()

    try:
        manifest = read_manifest(args.manifest)
        module = args.manifest.resolve().parent
        verify_artifacts(manifest, module, args.check_files)
        if args.dictionary_source is not None:
            verify_dictionary(manifest, module, args.dictionary_source.read_bytes())
        elif args.fetch_dictionary_source:
            verify_dictionary(manifest, module, fetch(derivation_url(manifest)))
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(f"Model provenance verification failed: {error}", file=sys.stderr)
        return 1

    checked = "; the dictionary derivation matches" if args.dictionary_source or args.fetch_dictionary_source else ""
    print(f"Model provenance lock is valid{checked}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

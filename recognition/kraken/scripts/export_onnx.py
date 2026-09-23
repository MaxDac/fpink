#!/usr/bin/env python3
"""Recorded Kraken ONNX export entry point.

This script intentionally fails until the final Kraken/PyTorch module export is
reviewed against the exact selected checkpoint. It exists so the repository
records where reproducible export work belongs without letting Gradle or Android
runtime download unchecked model bytes.
"""

from __future__ import annotations

import argparse
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-repo", required=True)
    parser.add_argument("--model-file", required=True)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    raise SystemExit(
        "Kraken ONNX export is not yet automated. Download "
        f"{args.model_repo}/{args.model_file}, export the recognizer with a "
        "reviewed fixed input contract, write ppocrv6-medium-recognition.onnx "
        "and ppocrv6-medium-alphabet.txt under "
        f"{args.output_dir}, then pin their hashes in artifacts.lock.json."
    )


if __name__ == "__main__":
    main()

"""Fetch the pinned official PP-OCRv6 ONNX exports and derive FPInk's bundled assets.

Not run by Gradle: public builds never download model bytes. Run it only to reproduce or
update `src/main/assets/recognition/ppocrv6`, then copy the printed sizes and hashes into
`artifacts.lock.json` and `PpOcrV6Models.kt`.

    py -m venv .ppocrv6
    .\\.ppocrv6\\Scripts\\python.exe -m pip install huggingface_hub pyyaml onnxruntime numpy
    .\\.ppocrv6\\Scripts\\python.exe recognition\\models\\scripts\\fetch_ppocrv6.py
"""

from __future__ import annotations

import argparse
import hashlib
import pathlib
import shutil

import yaml
from huggingface_hub import hf_hub_download

DETECTOR = ("PaddlePaddle/PP-OCRv6_small_det_onnx", "28fe5895c24fd108c19eb3e8479f4ab385fbfc62",
            "d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e")
RECOGNIZER = ("PaddlePaddle/PP-OCRv6_medium_rec_onnx", "50c7eacafc52fa7bcf4194e8cd08e46f8558504b",
              "9c09abf0957f7968c7586464b7397b84ad2387a0497a351af40e9acc71b673ba")


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 16), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fetch(repo: str, revision: str, expected: str) -> tuple[pathlib.Path, dict]:
    model = pathlib.Path(hf_hub_download(repo, "inference.onnx", revision=revision))
    actual = sha256(model)
    if actual != expected:
        raise SystemExit(f"{repo}@{revision} inference.onnx hash {actual} != pinned {expected}")
    config = pathlib.Path(hf_hub_download(repo, "inference.yml", revision=revision))
    return model, yaml.safe_load(config.read_text(encoding="utf-8"))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=pathlib.Path,
                        default=pathlib.Path(__file__).resolve().parents[1] / "src/main/assets/recognition/ppocrv6")
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)

    det_model, det_config = fetch(*DETECTOR)
    post = det_config["PostProcess"]
    print("detector post-processing:", {k: post[k] for k in ("thresh", "box_thresh", "unclip_ratio", "max_candidates")})
    shutil.copyfile(det_model, args.output_dir / "PP-OCRv6_small_det.onnx")

    rec_model, rec_config = fetch(*RECOGNIZER)
    characters = rec_config["PostProcess"]["character_dict"]
    if any("\n" in c or "\r" in c or len(c) == 0 for c in characters):
        raise SystemExit("Dictionary entries must be non-empty and single-line")
    import onnxruntime  # noqa: PLC0415 - only needed for the shape check
    classes = onnxruntime.InferenceSession(str(rec_model)).get_outputs()[0].shape[-1]
    # PaddleOCR CTCLabelDecode: class 0 is the CTC blank, then the dictionary, then a space.
    if classes != len(characters) + 2:
        raise SystemExit(f"Recognizer has {classes} classes but the dictionary implies {len(characters) + 2}")
    shutil.copyfile(rec_model, args.output_dir / "PP-OCRv6_medium_rec.onnx")
    (args.output_dir / "PP-OCRv6_medium_rec_dict.txt").write_bytes(("\n".join(characters) + "\n").encode("utf-8"))

    for path in sorted(args.output_dir.iterdir()):
        print(f"{path.name}: {path.stat().st_size} bytes sha256={sha256(path)}")


if __name__ == "__main__":
    main()

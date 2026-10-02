#!/usr/bin/env python3
"""Reproducible export of the Kraken PP-OCRv6 medium recognizer to ONNX.

This is the exact, executed procedure used to produce
``ppocrv6-medium-recognition.onnx`` and ``ppocrv6-medium-alphabet.txt`` under
``src/main/assets/kraken/``. It intentionally runs in a throwaway Python
environment *outside* this repository (Kraken/PyTorch never ship in the app or
in Gradle); only this script and the resulting pinned artifacts are committed.

Usage (see README.md "Reproducing the export" for the full, exact commands
including venv setup)::

    python export_onnx.py \\
        --model-repo small-models-for-glam/kraken-ppocrv6-medium \\
        --model-file medium.safetensors \\
        --revision 01b574f071bf21cff7d7e2a4f38966925dddc30a \\
        --output-dir ./out

Requires (installed only in the scratch venv, never in the repo or app):
    pip install "kraken>=7.1.0" torch onnx onnxscript onnxruntime safetensors huggingface_hub

What this does, precisely:
    1. Downloads the pinned checkpoint from the Hugging Face mirror of the
       Zenodo-hosted Kraken model repository via ``huggingface_hub``.
    2. Loads it with ``kraken.models.loaders.load_models`` (the same loader
       the real ``kraken`` CLI uses), which reconstructs the
       ``kraken.lib.ppocr.model.PPOCRv6Model`` wrapping a
       ``kraken.lib.ppocr.network.PPOCRv6Recognizer`` and its embedded
       single-codepoint-per-label CTC codec (``model.user_metadata['codec']``,
       a grapheme -> [label] map with no multi-token entries for this
       checkpoint — verified by this script, see ``_require_singleton_codec``).
    3. Wraps the network's ``forward(image, seq_lens=None)`` in
       ``_UnpaddedRecognizerWrapper``: this checkpoint's inference config only
       ever needs ``seq_lens`` to build a padding attention mask for batched,
       padded inference (see ``kraken.lib.ppocr.network._lengths_and_mask``).
       Because the Android integration always recognizes one already-cropped
       line at a time with no padding (batch size 1), ``seq_lens=None`` is
       exactly equivalent to kraken's own unpadded/single-item behaviour and
       lets the exported graph skip the mask entirely.
    4. Exports the wrapped module with the ``torch.onnx`` dynamo exporter
       (``dynamo=True``), which is what correctly traces the dynamic-width
       reshape kraken's PPLCNetV4 stem uses (``x.shape[-1]``-derived pooling);
       the legacy TorchScript-tracing exporter fails on this exact op, see the
       "Known dynamo-vs-legacy exporter dependency" note in README.md. Weights
       are forced inline into the single ``.onnx`` file
       (``external_data=False``) rather than split into a companion
       ``*.onnx.data`` file, since the Android integration bundles exactly one
       asset file per model.
    5. Verifies the exported graph is numerically equivalent to the original
       PyTorch module (max abs diff on logits, several widths) before writing
       anything.
    6. Extracts the codec's label -> grapheme mapping (label 0 is always the
       CTC blank and is never written) into a newline-delimited, UTF-8
       alphabet file ordered by ascending label id, matching exactly what
       ``KrakenCtcDecoder.decode`` and ``KrakenOnnxRecognizer`` expect on the
       Android side (``alphabet[label - 1]``).
    7. Prints the SHA-256 hash and byte length of both output files so they
       can be pinned in ``artifacts.lock.json`` and
       ``KrakenOcrProvider.assets``.

This script does not, and must not, attempt to export Kraken's separate
``blla`` line-segmentation network: this integration deliberately uses a
classical row-projection heuristic on-device instead (see
``KrakenLineSegmenter`` and the disclosure in README.md).
"""

from __future__ import annotations

import argparse
import hashlib
import sys
from pathlib import Path
from typing import Optional


def _sha256_and_size(path: Path) -> tuple[str, int]:
    digest = hashlib.sha256()
    total = 0
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
            total += len(chunk)
    return digest.hexdigest(), total


def _require_singleton_codec(c2l: dict) -> None:
    """Fails closed if the checkpoint's codec is not a plain 1 grapheme <-> 1
    label mapping, since the Android decoder only implements that simpler
    (and, for this checkpoint, verified-accurate) case."""
    for grapheme, labels in c2l.items():
        if len(labels) != 1:
            raise SystemExit(
                f"Codec entry {grapheme!r} maps to {len(labels)} labels; this "
                "export script and KrakenCtcDecoder only support singleton "
                "(1 grapheme <-> 1 label) codecs. Refusing to export."
            )
        if len(grapheme) != 1:
            raise SystemExit(
                f"Codec key {grapheme!r} is not a single code point; this "
                "export script only supports single-code-point graphemes."
            )


def export(model_repo: str, model_file: str, output_dir: Path, revision: Optional[str] = None) -> None:
    import torch
    from huggingface_hub import hf_hub_download
    from kraken.models.loaders import load_models

    output_dir.mkdir(parents=True, exist_ok=True)

    checkpoint_path = hf_hub_download(repo_id=model_repo, filename=model_file, revision=revision)
    print(f"Downloaded checkpoint: {checkpoint_path}", file=sys.stderr)

    models = load_models(checkpoint_path, tasks=["recognition"])
    if len(models) != 1:
        raise SystemExit(f"Expected exactly 1 recognition model in {model_file}, found {len(models)}.")
    model = models[0]
    model.eval()

    height = model.user_metadata["height"]
    c2l = model.user_metadata["codec"]
    _require_singleton_codec(c2l)
    num_classes = model.num_classes
    print(
        f"variant={model.variant} num_classes={num_classes} height={height} "
        f"seg_type={model.seg_type} legacy_polygons={model.use_legacy_polygons}",
        file=sys.stderr,
    )

    class _UnpaddedRecognizerWrapper(torch.nn.Module):
        """(N, 3, height, W) -> (N, W', num_classes) with W' = floor(W/8)-ish,
        assuming a single unpadded line (see module docstring point 3)."""

        def __init__(self, net: torch.nn.Module):
            super().__init__()
            self.net = net

        def forward(self, image: torch.Tensor) -> torch.Tensor:
            logits, _ = self.net(image, None)  # (N, num_classes, 1, W')
            return logits.squeeze(2).permute(0, 2, 1)  # (N, W', num_classes)

    wrapper = _UnpaddedRecognizerWrapper(model.nn)
    wrapper.eval()

    onnx_path = output_dir / "ppocrv6-medium-recognition.onnx"
    example = torch.rand(1, 3, height, 256)
    from torch.export import Dim

    width_dim = Dim("width", min=32, max=8192)
    torch.onnx.export(
        wrapper,
        (example,),
        str(onnx_path),
        input_names=["image"],
        output_names=["logits"],
        dynamic_shapes={"image": {3: width_dim}},
        opset_version=18,
        dynamo=True,
        # The dynamo exporter defaults to splitting large weight tensors into a
        # companion ``*.onnx.data`` file. The Android integration bundles a
        # single asset file per model (see KrakenOcrProvider.assets /
        # artifacts.lock.json), so weights must be embedded inline instead.
        external_data=False,
    )
    print(f"Exported ONNX graph: {onnx_path}", file=sys.stderr)

    _verify_onnx_matches_pytorch(wrapper, onnx_path, height)

    alphabet_path = output_dir / "ppocrv6-medium-alphabet.txt"
    max_label = max(v[0] for v in c2l.values())
    if max_label != num_classes - 1:
        raise SystemExit(
            f"Codec max label {max_label} does not equal num_classes-1 "
            f"({num_classes - 1}); refusing to export a mismatched alphabet."
        )
    label_to_grapheme = {v[0]: k for k, v in c2l.items()}
    missing = [i for i in range(1, max_label + 1) if i not in label_to_grapheme]
    if missing:
        raise SystemExit(f"Codec is missing labels: {missing[:10]}...")
    with alphabet_path.open("w", encoding="utf-8", newline="\n") as fh:
        for label in range(1, max_label + 1):
            fh.write(label_to_grapheme[label] + "\n")
    print(f"Wrote alphabet ({max_label} entries): {alphabet_path}", file=sys.stderr)

    for path in (onnx_path, alphabet_path):
        digest, size = _sha256_and_size(path)
        print(f"{path.name}: sha256={digest} bytes={size}")


def _verify_onnx_matches_pytorch(wrapper, onnx_path: Path, height: int) -> None:
    import numpy as np
    import onnxruntime as ort
    import torch

    sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    torch.manual_seed(0)
    worst = 0.0
    for width in (192, 256, 300, 512, 800, 1200):
        x = torch.rand(1, 3, height, width)
        with torch.no_grad():
            torch_out = wrapper(x).numpy()
        onnx_out = sess.run(None, {"image": x.numpy()})[0]
        if torch_out.shape != onnx_out.shape:
            raise SystemExit(f"Shape mismatch at width={width}: torch={torch_out.shape} onnx={onnx_out.shape}")
        diff = float(np.abs(torch_out - onnx_out).max())
        worst = max(worst, diff)
        print(f"  verify width={width} torch_shape={torch_out.shape} max_abs_diff={diff:.6f}", file=sys.stderr)
    if worst > 1e-2:
        raise SystemExit(f"ONNX export diverges from PyTorch (max abs diff {worst:.6f} > 1e-2); refusing to publish.")
    print(f"ONNX export verified against PyTorch (worst-case max abs diff {worst:.6f}).", file=sys.stderr)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-repo", required=True, help="Hugging Face repo id, e.g. small-models-for-glam/kraken-ppocrv6-medium")
    parser.add_argument("--model-file", required=True, help="Checkpoint filename inside the repo, e.g. medium.safetensors")
    parser.add_argument("--revision", default=None, help="Optional pinned Hugging Face revision/commit SHA")
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    export(args.model_repo, args.model_file, args.output_dir, args.revision)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

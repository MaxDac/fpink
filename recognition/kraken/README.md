# Kraken OCR Android adapter

`KrakenOcrProvider(context)` adds a second public offline provider ID
(`RecognitionProviderId.KRAKEN`) and a Python-free Android integration seam for
Kraken-compatible ONNX inference. The module is intentionally fail-closed today:
the reviewed ONNX export files are **not** bundled, so readiness reports
`ModelUnavailable` instead of pretending a heuristic recognizer is Kraken.

## Validation status and release gate

The app/provider selection wiring, ServiceLoader registration, model-status UI,
Kraken line-projection segmentation helper, and CTC greedy decoder have JVM test
coverage. Android readiness checks fail explicitly when the reviewed ONNX export
is absent. No network permission or runtime download is used.

End-to-end Kraken recognition has **not** been verified yet because this commit
does not include reviewed exported Kraken model weights. This is a known
limitation, not an accuracy claim. The provider must not be advertised as usable
until `ppocrv6-medium-recognition.onnx` and `ppocrv6-medium-alphabet.txt` are
added with exact byte counts/SHA-256 values and the Android instrumentation suite
is run on an emulator/device.

## Exact build inputs

* Android library, minSdk **26**, compileSdk **37**, Java/Kotlin bytecode **17**.
* ONNX Runtime Android from Maven Central: `com.microsoft.onnxruntime:onnxruntime-android:1.30.0`.
* Supported ABIs in readiness: **arm64-v8a** and **x86_64**, matching the normal
  ONNX Runtime Android emulator/device targets used by this project. Unsupported
  devices receive an explicit `UnsupportedDevice` result.
* No Python, PyTorch, Chaquopy, network fetch, or dynamic model download is used
  at build or runtime.

## Model choice and provenance

Selected recognition model: **PP-OCRv6 medium multilingual text recognition base
model for Kraken**, canonical Zenodo DOI **10.5281/zenodo.21788410**, mirrored
by Small Models for GLAM at
<https://huggingface.co/small-models-for-glam/kraken-ppocrv6-medium>.

Reasoning:

* The model card states it is a CTC-based Kraken line recognizer, which matches
  FPInk's existing Paddle CTC decoding shape and can be hosted through ONNX
  Runtime without Python.
* The training mix includes Latin script and English (2,005 English evaluation
  lines; reported English CER 5.90%, WER 20.35% on the model card's held-out
  split).
* The canonical release/mirror states Apache-2.0 licensing, which is compatible
  with the public GPL-3.0-only app and F-Droid distribution.
* It is a recognizer, not a page segmenter. Until Kraken's `blla` segmentation
  network is exported and validated, this module documents and tests a pragmatic
  classical row-projection line segmenter. That is a fidelity limitation relative
  to Kraken's reference CLI and must be measured on real notes before release.

## Export procedure

The export script is recorded in `scripts/export_onnx.py` as the reproducibility
entry point. It is deliberately not run by Gradle because clean public builds must
not download model bytes or create unchecked binaries. A maintainer preparing the
real assets should:

```powershell
python -m venv .kraken-export
.\.kraken-export\Scripts\python.exe -m pip install --upgrade pip
.\.kraken-export\Scripts\python.exe -m pip install "kraken>=7.1.0" torch onnx safetensors huggingface_hub
.\.kraken-export\Scripts\python.exe recognition\kraken\scripts\export_onnx.py `
  --model-repo small-models-for-glam/kraken-ppocrv6-medium `
  --model-file medium.safetensors `
  --output-dir recognition\kraken\src\main\assets\kraken
```

After export, update `artifacts.lock.json` and the `KrakenOcrProvider.assets`
list with the exact byte counts and SHA-256 hashes, then run:

```powershell
.\gradlew.bat :recognition:kraken:verifyKrakenArtifacts
.\gradlew.bat :recognition:kraken:testDebugUnitTest
.\gradlew.bat :recognition:kraken:connectedDebugAndroidTest
.\gradlew.bat build
```

Only then should this README's validation section be updated with emulator output
and measured OCR behavior.

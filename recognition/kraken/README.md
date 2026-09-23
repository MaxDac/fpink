# Kraken OCR Android adapter

`KrakenOcrProvider(context)` adds a second public offline provider ID
(`RecognitionProviderId.KRAKEN`) and a Python-free Android integration that runs
a real, reviewed ONNX export of a Kraken-compatible recognizer
(`small-models-for-glam/kraken-ppocrv6-medium`) via `onnxruntime-android`. The
module bundles the exported weights as an app asset; no Python, PyTorch,
Chaquopy, network fetch, or dynamic model download happens at build or runtime.

## Validation status and release gate

**What is proven, with evidence:**

* The ONNX export is numerically equivalent to the source PyTorch model:
  max abs logit difference across widths 192-1200px was **≤ 0.000164**
  (float32 rounding noise), verified by `scripts/export_onnx.py`'s built-in
  check before it will write any output file.
* A genuine Python-side OCR sanity check (outside the Android app, in the
  export venv) rendered 6 synthetic text lines with PIL — 3 sentences in
  Windows' Segoe Script cursive font and the same 3 in Arial as a control —
  ran them through the exact preprocessing/inference/CTC-decode pipeline this
  module implements, and **all 6 decoded with exact character-for-character
  accuracy**. This shows the export, preprocessing contract, and decoder logic
  are correct in isolation.
* **On-device, on an actual booted Android emulator (`Pixel_7` AVD, API 34,
  x86_64), `:recognition:kraken:connectedDebugAndroidTest` passes all 4 tests**,
  including `recognizesSyntheticTextLine`, which renders `"Hello Kraken OCR"`
  on-device with Android's own `Canvas`/`Typeface` (italic serif, since the
  emulator image has no bundled genuine cursive font — see disclosure below),
  runs it through the real `KrakenOcrProvider(context).recognize(...)` call
  (real segmentation, real ONNX Runtime session, real CTC decode against the
  bundled alphabet), and asserts on the result. The on-device logcat output for
  that run was:

  ```
  I KrakenRecognitionTest: recognized='Hello Kraken OCR' expected='Hello Kraken OCR'
  ```

  i.e. an **exact match** for this synthetic line. `blankPageProducesNoRegions`
  confirms an all-white page yields zero regions, and
  `testApkDoesNotDeclareInternetPermission` confirms no `INTERNET` permission is
  requested by the test APK.
* `./gradlew.bat build` (the full multi-module suite: `core:ai`, `core:model`,
  `recognition:paddle`, `recognition:kraken`, `app` foss+full variants) passes
  with these changes, i.e. this integration did not regress Paddle, the app
  module, or the provider-abstraction contract tests.

**What is honestly *not* proven, and known limitations:**

* One synthetic short line in one font is not a corpus-level accuracy claim.
  No CER/WER measurement against real handwritten notes has been done for this
  module (contrast with the upstream model card's own reported English CER
  5.90% / WER 20.35% on its held-out evaluation split, which describes the
  *reference PyTorch* model's accuracy, not this Android/bilinear/heuristic
  variant).
* Kraken's own `blla` line-segmentation network was **not** exported; this
  module uses a classical row-projection heuristic (`KrakenLineSegmenter`)
  instead, as pre-approved for this integration. This is a real fidelity
  limitation versus Kraken's reference CLI on complex/multi-column/skewed pages
  and has not been measured.
* The Android port resizes each cropped line to the model's fixed height using
  **bilinear** interpolation, whereas Kraken's reference preprocessing uses
  **Lanczos** resizing. This is a disclosed, deliberate simplification (no
  Lanczos scaler is bundled on Android and pulling in an image-processing
  dependency for only this wasn't judged worth it). It did not change the
  decoded output in the Python-side synthetic test, but Lanczos was never
  actually exercised end-to-end on-device, so any accuracy delta this causes on
  real handwriting is unmeasured.
* Only `arm64-v8a` and `x86_64` are treated as supported ABIs (see "Exact build
  inputs"); other ABIs fail closed with `UnsupportedDevice` rather than
  silently degrading.
* The full capture -> Settings -> select Kraken -> recognize UI flow was not
  driven end-to-end through Compose UI automation in this validation pass; the
  `connectedDebugAndroidTest` instrumentation exercises `KrakenOcrProvider`
  directly (constructing a `PreparedImage` in-process), which covers the real
  segmentation/inference/decode pipeline but not the Settings screen's
  provider-picker interaction. That UI wiring has JVM-level ViewModel/state
  tests (see `app/src/test`) but no on-emulator UI-automation pass yet.

## Exact build inputs

* Android library, minSdk **26**, compileSdk **37**, Java/Kotlin bytecode **17**.
* ONNX Runtime Android from Maven Central: `com.microsoft.onnxruntime:onnxruntime-android:1.30.0`.
* Supported ABIs in readiness: **arm64-v8a** and **x86_64**, matching the normal
  ONNX Runtime Android emulator/device targets used by this project. Unsupported
  devices receive an explicit `UnsupportedDevice` result.
* No Python, PyTorch, Chaquopy, network fetch, or dynamic model download is used
  at build or runtime — the export only ever runs in a throwaway environment
  outside this repository (see "Export procedure").

## Model choice and provenance

Selected recognition model: **PP-OCRv6 medium multilingual text recognition base
model for Kraken**, canonical Zenodo DOI **10.5281/zenodo.21788410**, mirrored
by Small Models for GLAM at
<https://huggingface.co/small-models-for-glam/kraken-ppocrv6-medium>, checkpoint
file `medium.safetensors`, pinned Hugging Face revision
`01b574f071bf21cff7d7e2a4f38966925dddc30a` (recorded in `artifacts.lock.json`).

Reasoning:

* The model card states it is a CTC-based Kraken line recognizer, which matches
  FPInk's existing Paddle CTC decoding shape and can be hosted through ONNX
  Runtime without Python.
* The training mix includes Latin script and English (2,005 English evaluation
  lines; reported English CER 5.90%, WER 20.35% on the model card's held-out
  split — this is the *upstream* PyTorch model's measured accuracy, not a claim
  about this Android integration; see "Validation status" above for what this
  module itself has measured).
* The canonical release/mirror states Apache-2.0 licensing, which is compatible
  with the public GPL-3.0-only app and F-Droid distribution.
* It is a recognizer, not a page segmenter. Kraken's `blla` segmentation
  network was deliberately not exported (see limitations above); this module
  uses a documented, tested classical row-projection line segmenter instead.

## Model architecture, as discovered while exporting

`medium.safetensors` loads via `kraken.models.loaders.load_models(...)` into a
`kraken.lib.ppocr.model.PPOCRv6Model` (`variant='medium'`, `num_classes=1623`
i.e. 1622 graphemes + 1 CTC blank, fixed input `height=96`). The underlying
network (`model.nn`, a `kraken.lib.ppocr.network.PPOCRv6Recognizer`: PPLCNetV4
backbone -> LightSVTR neck -> CTCHead) has signature
`forward(image, seq_lens=None) -> (logits, out_lens)` with
`logits.shape == (N, 1623, 1, W')`, `W' = floor(W / 8)`. The codec
(`model.user_metadata['codec']`) is a simple **singleton** grapheme<->label
mapping (every grapheme is exactly 1 code point, every label list has exactly 1
entry, ids span 1..1622 contiguously) — `export_onnx.py` verifies this and
refuses to export if it were ever not true, since the Android decoder only
implements the simpler singleton case.

`seq_lens=None` is exactly correct for the single-line, unpadded, batch-size-1
inference this integration always performs: kraken only uses `seq_lens` to
build a padding-attention mask for batched/padded inputs
(`kraken.lib.ppocr.network._lengths_and_mask`), which never applies here.

### Preprocessing and decoding contract (must match on both sides)

Derived from `kraken.models.ctc.CTCRecognitionInferenceMixin` and
`kraken.lib.dataset.utils.ImageInputTransforms`, and replicated exactly in
`KrakenOnnxRecognizer` except for the disclosed Lanczos-vs-bilinear resize:

1. Convert the line crop to RGB.
2. Resize to fixed height 96, aspect-ratio-preserving width (Kraken: Lanczos;
   this Android port: bilinear — disclosed limitation above).
3. Pad 16px solid white on the left and right.
4. Scale channel values to `[0, 1]`, then invert: `1 - x` (ink -> high value,
   white background -> ~0).
5. Run the ONNX model; take `argmax` over the class dimension per timestep.
6. Greedy CTC decode: collapse consecutive repeated labels, drop label `0`
   (blank), map each surviving label `l` to `alphabet[l - 1]` (the bundled
   `ppocrv6-medium-alphabet.txt`, one grapheme per line in ascending label
   order), and concatenate.

## Known dynamo-vs-legacy ONNX exporter dependency

`torch.onnx.export(..., dynamic_axes=...)` (PyTorch's legacy TorchScript-tracing
exporter) **fails** on this model with a `SymbolicValueError` on a dynamic
`aten::Gather`-derived reshape inside the PPLCNetV4 stem block. The **dynamo
exporter** (`torch.onnx.export(..., dynamo=True)` with `torch.export.Dim` for
the dynamic width axis) traces this correctly. This requires the extra
`onnxscript` package (not installed by default alongside `torch`/`onnx`). Do
not revert to a `dynamic_axes`-only legacy export for this model — it does not
work.

The dynamo exporter also defaults to splitting large weight tensors into a
companion `*.onnx.data` external-data file once the graph exceeds a size
threshold. This integration bundles exactly **one** asset file per model (see
`KrakenOcrProvider.assets` / `artifacts.lock.json`), so the export passes
`external_data=False` to force all ~63.9 MiB of weights to be embedded inline
in the single `ppocrv6-medium-recognition.onnx` file (64,204,317 bytes).

## Export procedure

The export script is recorded in `scripts/export_onnx.py` as the reproducibility
entry point and reproduces the exact procedure used to produce the bundled
assets. It deliberately is not run by Gradle: clean public builds must not
download model bytes or create unchecked binaries. To reproduce or update the
export:

```powershell
python -m venv .kraken-export
.\.kraken-export\Scripts\python.exe -m pip install --upgrade pip
.\.kraken-export\Scripts\python.exe -m pip install "kraken>=7.1.0" torch onnx onnxscript onnxruntime safetensors huggingface_hub
$env:PYTHONIOENCODING = "utf-8"  # kraken/torch.onnx print non-cp1252-safe characters on Windows consoles
.\.kraken-export\Scripts\python.exe recognition\kraken\scripts\export_onnx.py `
  --model-repo small-models-for-glam/kraken-ppocrv6-medium `
  --model-file medium.safetensors `
  --revision 01b574f071bf21cff7d7e2a4f38966925dddc30a `
  --output-dir recognition\kraken\src\main\assets\kraken
```

The script prints a SHA-256 hash and byte count for each output file; update
`artifacts.lock.json` and the `KrakenOcrProvider.assets` list with those exact
values, then run:

```powershell
.\gradlew.bat :recognition:kraken:verifyKrakenArtifacts
.\gradlew.bat :recognition:kraken:testDebugUnitTest
.\gradlew.bat :recognition:kraken:connectedDebugAndroidTest   # requires a booted emulator/device
.\gradlew.bat build
```

Update this README's validation section with the real, honestly-reported
emulator output and any measured OCR behavior whenever the export changes.


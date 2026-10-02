# Recognition models

On-device models used by the built-in [recognition strategies](../strategies/README.md).
Each class implements one `:core:ai` model interface and runs through the shared
ONNX Runtime helpers in `:recognition:runtime`.

| Class | Interface | Asset | Version string |
|---|---|---|---|
| `PpOcrV6SmallDetector` | `TextDetector` | `ppocrv6/PP-OCRv6_small_det.onnx` | `PP-OCRv6_small_det@28fe5895` |
| `PpOcrV6MediumRecognizer` | `TextLineRecognizer` | `ppocrv6/PP-OCRv6_medium_rec.onnx` + dictionary | `PP-OCRv6_medium_rec@50c7eacaf` |
| `KrakenRecognizer` | `TextLineRecognizer` | `kraken/ppocrv6-medium-recognition.onnx` + alphabet | `kraken-ppocrv6-medium/zenodo-10.5281-zenodo.21788410` |

## Provenance

All bundled models are Apache-2.0. The licence texts and `NOTICE.txt` are packaged
under `src/main/assets/recognition/`.

| Asset | Bytes | Source | Pinned revision |
|---|---:|---|---|
| PP-OCRv6 small detector (`inference.onnx`) | 9,880,512 | [PaddlePaddle/PP-OCRv6_small_det_onnx](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx) | `28fe5895c24fd108c19eb3e8479f4ab385fbfc62` |
| PP-OCRv6 medium recognizer (`inference.onnx`) | 76,554,979 | [PaddlePaddle/PP-OCRv6_medium_rec_onnx](https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_rec_onnx) | `50c7eacafc52fa7bcf4194e8cd08e46f8558504b` |
| PP-OCRv6 dictionary (from `inference.yml`, 18,708 labels) | 74,947 | same as above | same as above |
| Kraken PP-OCRv6-medium recognizer (ONNX export) | 64,204,317 | [Zenodo 10.5281/zenodo.21788410](https://doi.org/10.5281/zenodo.21788410), mirrored at [small-models-for-glam/kraken-ppocrv6-medium](https://huggingface.co/small-models-for-glam/kraken-ppocrv6-medium) | `01b574f071bf21cff7d7e2a4f38966925dddc30a` |
| Kraken alphabet (1,622 labels) | 5,615 | same as above | same as above |

Every file is pinned by size and SHA-256 in [`artifacts.lock.json`](artifacts.lock.json).
`:recognition:models:verifyModelArtifacts` checks the pins on every `preBuild`. At runtime,
the files are copied into `noBackupFilesDir` and their hashes are checked again before
ONNX Runtime opens them.

To regenerate the assets:

```powershell
py recognition/models/scripts/fetch_ppocrv6.py       # downloads the PP-OCRv6 ONNX files and extracts the dictionary
py recognition/models/scripts/export_kraken_onnx.py  # exports the Kraken safetensors checkpoint to ONNX
```

If any asset changes, including `NOTICE.txt`, update its entry in `artifacts.lock.json`.

## Preprocessing contracts

- **Detector**
  - BGR input, normalised with the ImageNet mean and std.
  - The long side is resized to at most 960 px, and both sides are rounded to a multiple of 32.
  - Post-processing is a DB step in Kotlin (`DbPostProcessing`) with threshold 0.2,
    box threshold 0.45, unclip ratio 1.4 and at most 3,000 candidates. These values come
    from the pinned `inference.yml`. They intentionally differ from the old Paddle Lite
    values (0.3 / 0.6 / 1.5).
- **PP-OCRv6 recognizer**
  - BGR input, height 48, width `ceil(48 · w / h)` padded with zeros to at least 320 px.
    Width is capped at 2,048 px.
  - Output is decoded with greedy CTC over the dictionary.
- **Kraken recognizer**
  - RGB input scaled to [0, 1] and inverted, height 96, with 16 px of white padding on
    the left and right. Width is capped at 4,096 px.
  - Output is decoded with greedy CTC, then normalised from NFD to NFC.

## Validation

- **JVM**
  - `.\gradlew.bat :recognition:models:test` checks the asset pins.
  - `.\gradlew.bat :recognition:runtime:test` covers DB post-processing, detector input
    sizing and normalisation, quad cropping and the CTC decoder.
  - Asset materialisation and hash checks run on the device tests below.
- **Device**
  - `.\gradlew.bat :recognition:strategies:connectedDebugAndroidTest` was run on a Pixel 7
    x86_64 API 34 emulator.
  - Printed recognized the synthetic line "Hello printed world" exactly, and Cursive
    recognized the italic line "Hello Kraken OCR" exactly. A blank page returned no lines.

## Limitations

- Kraken's reference implementation resizes with Lanczos; this module uses bilinear
  resizing. Expect small differences in results.
- On-device validation used synthetic one-line images only. No character error rate
  has been measured on real handwritten notes yet.
- The PP-OCRv6 medium recognizer has not been timed on a phone CPU. If it is too slow,
  the official PP-OCRv6 small recognizer can replace it for Printed.
- On ARM-translated x86 devices, recognition fails closed with an unsupported-device
  error.
- Together the models add about 150 MB of uncompressed assets. The unsigned FOSS release
  APK is 185,992,278 bytes, compared with 119,379,307 bytes for the signed
  0.1.0-preview.11 Paddle Lite release, an increase of about 66.6 MB.

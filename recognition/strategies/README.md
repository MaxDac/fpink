# Recognition strategies

A `RecognitionStrategy` (from `:core:ai`) turns a `PreparedImage` into a
`RecognitionDocument`. This module provides the two strategies bundled in every
build. Both run fully on-device.

| Strategy | ID | Detector | Line recognizer |
|---|---|---|---|
| Printed | `printed` | PP-OCRv6 small | official PP-OCRv6 medium |
| Cursive (default) | `cursive` | PP-OCRv6 small | Kraken PP-OCRv6-medium, NFC-normalised |

The models live in [`:recognition:models`](../models/README.md). The shared
ONNX, image and decoding code lives in `:recognition:runtime`.

## DetectThenRecognizeStrategy

Both strategies are a `DetectThenRecognizeStrategy` with a different recognizer:

1. Reject prepared images outside 16-8192 px per side or above 16 million pixels.
2. Check the combined readiness of both models.
3. Detect line quads, then sort them in reading order: top to bottom, and left
   to right within a row.
4. Crop each quad with an affine warp. Lines at least 1.5 times taller than wide
   are rotated.
5. Recognize each crop. Lines with blank text or confidence below 0.5 are dropped.
6. Return polygons normalised to `[0, 1]` of the prepared image. The document's
   `modelVersion` is `"<detector version>+<recognizer version>"`.

Coroutine cancellation is checked between lines. A process-wide mutex runs one
page at a time because the models are large, and the ONNX sessions are released
after each page. Errors are returned as `RecognitionError` values; no strategy
falls back to another strategy or to a network service.

## Selection

`BuiltInStrategies` exposes the IDs, labels and factories. The app also lists
plugin strategies from `RecognitionStrategyRegistry` (private `full` builds only).
Settings → Default recognition stores the default (Cursive unless changed). The
camera route offers a per-capture picker with the default preselected; gallery,
file and share imports always use the default.

## Tests

- JVM: `.\gradlew.bat :recognition:strategies:test` covers composition, reading
  order, confidence filtering, normalised polygons, cancellation and errors with
  fake models.
- Device: `.\gradlew.bat :recognition:strategies:connectedDebugAndroidTest` runs
  both strategies on the bundled models. It checks readiness, a synthetic printed
  line, a synthetic italic line and a blank page, and that the test APK requests
  no `INTERNET` permission.

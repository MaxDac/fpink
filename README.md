# FPInk

An Android app for capturing handwritten fountain-pen notes, recognizing text,
and preserving detected ink colours. Offline PaddleOCR is the default;
Azure recognition is optional and explicitly selected.

## Build and releases

Use JDK 17, Android SDK 37, NDK `28.2.13676358`, and CMake `3.22.1`.
Run `.\gradlew.bat build` on Windows or `./gradlew build` on Linux.
The offline recognizer currently requires an ARM64 device.

See [architecture](docs/ARCHITECTURE.md), [offline recognition](recognition/paddle/README.md),
and [release setup, versioning and F-Droid readiness](docs/RELEASING.md).

## License

Copyright (C) 2026 FPInk contributors.

FPInk's original source code is licensed under the **GNU General Public License,
version 3 only** (`GPL-3.0-only`). You may redistribute and modify it under those
terms. FPInk comes without any warranty, including implied warranties of
merchantability or fitness for a particular purpose. See [LICENSE](LICENSE) for
the complete terms.

Third-party dependencies and bundled assets retain their own licenses; this
does not relicense their authors' work. Paddle runtime/model provenance and
bundled notices are documented in [recognition/paddle](recognition/paddle/README.md).
When distributing an APK, provide its corresponding source and preserve those
notices; the release workflow links each APK to its exact tagged source.

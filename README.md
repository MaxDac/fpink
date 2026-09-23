# FPInk

An Android app for capturing handwritten fountain-pen notes, recognizing text,
and preserving detected ink colours. This public build is 100% offline and
FOSS: bundled PaddleOCR is the only recognition provider.

## Correcting captured notes

After a new image is recognized and saved, the app opens a review screen containing
every generated paragraph note. Correct any transcription in place, then choose
**Done** to save the changed notes and return to the list. Leaving with unsaved
corrections asks for confirmation; discarding those corrections does not remove
the already captured notes.

## Selecting and deleting notes

Press and hold a note in the list to select it. Tap rows or their checkboxes to
change the selection, or use **Select all** to select every note in the list,
including those off screen. Clear the last selection or press Back to return to
opening notes normally.

Choose **Delete selected** and confirm once to permanently delete the selection.
Shared source images remain until their last note is deleted. If deletion fails,
the app stops and keeps remaining notes selected after refreshing; review the
selection before trying again. If refresh fails, use **Retry** first.

## Build and releases

Use JDK 17, Android SDK 37, NDK `28.2.13676358`, and CMake `3.22.1`.
Run `.\gradlew.bat build` on Windows or `./gradlew build` on Linux.
The offline recognizer currently requires an ARM64 device.

See [architecture](docs/ARCHITECTURE.md), [offline recognition](recognition/paddle/README.md),
[release setup and versioning](docs/RELEASING.md), and the
[F-Droid submission and maintenance runbook](docs/FDROID.md).

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

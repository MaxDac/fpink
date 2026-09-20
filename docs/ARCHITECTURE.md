# Architecture

## Modules and boundaries

| Module | Responsibility |
|--------|----------------|
| `:core:model` | Serializable notes, normalized image points and colour provenance |
| `:core:ai` | Two public contracts, Azure Read implementation, paragraph and colour processing |
| `:core:storage` | Portable note repository and recoverable batch publication |
| `:recognition:paddle` | Android/native CPU OCR, model assets and runtime provenance |
| `:app` | Image intake, permissions, encrypted settings, lifecycle, UI and manual DI |

All `:core:*` modules remain Android-free. Android `Bitmap`, `Uri`, Keystore and
native handles never cross their boundary. `AppContainer` wires the pipeline
without a plugin registry or DI framework.

## Pipeline

```text
CameraX / ACTION_GET_CONTENT chooser
    -> private source import, bounded decode, orientation normalization
    -> PreparedImage (encoded PNG/JPEG + matching ARGB pixels)
    -> explicitly selected RecognitionProvider
         PaddleOcrProvider: native PP-OCRv5 mobile CPU
         AzureReadProvider: prebuilt-read, API 2024-11-30
    -> RecognitionDocument (text regions + normalized geometry + paragraph hints)
    -> DefaultNoteProcessor
         logical paragraph grouping
         dominant foreground-ink cluster
    -> ParagraphDraft[]
    -> recoverable NoteRepository.saveBatch
    -> ordinary independent notes in the list
```

`RecognitionProvider.recognize` only recognizes text and layout. It neither creates
notes nor chooses their colours. `NoteProcessor.process` consumes the normalized
result and the same prepared colour pixels, without knowing vendor response DTOs.
Both return explicit `Result` values and propagate coroutine cancellation.

Paddle is selected by default. Azure is only constructed/used for an explicitly
selected online job; no error triggers another provider. Provider configuration
is snapshotted for each job rather than reread midway through recognition.

## Coordinates, paragraphs and colour

`ImagePoint` coordinates are finite values in `[0, 1]`, relative to the
orientation-corrected prepared image. Encoded bytes and the ARGB pixel buffer must
describe that same image. A provider must reverse any detection/crop/resize
transform before returning polygons.

Azure paragraph hints take precedence when available. Without hints, deterministic
layout heuristics consider reading order, relative spacing and indentation.
Visual newlines alone do not define notes. Soft wraps are joined without inventing
text, rewriting accents or automatically repairing ambiguous hyphens.

Colour sampling uses the union of text polygons, so overlapping detections do not
count pixels twice. Paper/background and long ruling are suppressed where
possible. Similar foreground colours are grouped and the cluster with the greatest
ink-pixel support wins, regardless of the number of recognized words.

`InkColorOrigin` distinguishes `DETECTED`, `DEFAULTED` and `USER_SELECTED`.
Unreliable colour estimation preserves text and defaults to black. An actual
detected black is not mislabeled as a fallback. Photo colour is not calibrated,
and layout/colour heuristics cannot resolve every ambiguous journal page.
The processor bounds input to 4,096 regions, 64 polygon vertices per region and
one million text characters. Colour work is limited to 131,072 samples per
paragraph and 1,048,576 per document, with a separate geometry-work budget.
Tiny strokes can be undersampled, and paragraphs with no distinguishing layout
cue cannot always be separated correctly. The detail editor shares the processor's
`inkColorName("#RRGGBB")` helper rather than maintaining a second colour-name map.

## Notes and persistence

The original `Note` fields and defaults remain readable: ID, timestamp, image
path, transcription, optional colour/confidence/model observations, edit flag,
tags and links. Tags/links remain reserved fields, not new category UI.

New optional/defaulted fields are:

| Field | Meaning |
|-------|---------|
| `sourceId` | Shared image/batch identity |
| `paragraphIndex` | Reading order within a batch |
| `paragraphPolygon` | Normalized region for this paragraph |
| `recognitionProvider`, `recognitionModelVersion` | OCR provenance |
| `inkColorOrigin` | Detected, defaulted or manually selected colour |

Each job has a stable source ID. Sibling note IDs are derived from that source
and paragraph index; the shared source is `images/{sourceId}.png`. Each paragraph
is a normal note record, not a sub-document that must be regenerated when edited.

`saveBatch(sourceId, imageBytes, imageExtension, notes)` writes the source and
notes atomically per file, then publishes a batch completion marker. New batch
notes are not visible through get/list until publication. Legacy flat notes
without batch metadata remain visible. A retry of an already committed batch
does not overwrite subsequent manual edits or create duplicate notes.

`staging/{sourceId}.json` records ownership before source/note writes.
`batches/{sourceId}.json` is the atomic publication and membership record. Recovery
rolls back unpublished owned files and preserves published batches. A cleanup
failure after publication can report an error even though the batch committed;
retry finishes recovery safely. Different original note IDs or image extensions
cannot reuse an existing batch ID.

Repository mutations are serialized. Storage read/write/cleanup failures are
surfaced rather than converted into an empty library. Deleting a paragraph first
updates committed membership atomically, then removes its record. Its source is
retained while any committed note references it, and removed after the last
reference. Empty batch markers remain as retry tombstones so even fully deleted
batches cannot be resurrected; they are omitted from the note list. Legacy
deletions use a recovery journal and the original stored image path. List ordering
preserves paragraph order for equal timestamps.
`isSourceCommitted(sourceId)` includes empty tombstones, so restoring an old job
does not upload or recognize its image again after all its notes were deleted.

`AndroidFileStore` uses atomic renames and file/directory synchronization.
Filesystem durability still requires device validation rather than only
in-memory repository tests.

The notes-list ViewModel owns stable-ID selection and the confirmation snapshot.
Long-press enters selection mode; clearing the last selection exits it. Select all
includes off-screen loaded notes but does not automatically include later arrivals.
Selection survives configuration changes, not process death.

Confirmed list deletion invokes the existing per-note delete operation sequentially
and stops at the first failure; it is not an all-or-nothing transaction. Refreshes
are coalesced and cannot overlap deletion. Reconciliation prunes selection only
after a successful repository list, because a failed delete may already have
committed. Unresolved cleanup/load failures block further destructive actions and
expose Retry without automatically deleting untouched notes. Repository recovery
still finishes previously committed deletion intents after interruption.

## Input, lifecycle and settings

Gallery uses a compatible image chooser, not an exclusive default-gallery API.
The app reads `ContentResolver` streams immediately into private storage; later
processing never depends on a transient external URI grant. Camera permission is
requested only on the camera path, and camera hardware is optional.

Capture uses ordinary unlocked activity recreation, respecting Android's
auto-rotate setting and supported orientations. Short-wide windows place the
preview or staged image beside scrollable controls and information when both
panes fit, reserving more control width for larger fonts. Portrait and narrower
windows use the vertical layout with bounded, scrollable information/action areas.
The selected camera mode is saved independently of the staged source ID, and a
restored staged image always takes precedence over reopening the camera.
CameraX use cases initialize from the attached preview's display. A display
listener updates capture rotation while that view is attached and its lifecycle
is started, including 180-degree changes without activity recreation; it ignores
other displays and unregisters on stop, detach or disposal. CameraX owns camera
activation through lifecycle binding and PreviewView owns preview transforms.
Import normalizes captured EXIF orientation once into upright PNG pixels; the UI
does not rotate the preview or prepared image manually.

The notes list reserves an inset-aware bottom row for Add notes (start/left in
LTR) and Take photo (end/right in LTR), keeping list rows and snackbars above
both actions. Both are hidden during note selection or deletion, preserving the
bulk-selection workflow. Add notes, including the empty-state action, opens the source
chooser without requesting permission. Take photo uses an explicit `openCamera`
navigation argument to enter the same capture screen's in-app CameraX preview;
it neither opens a gallery nor fires the shutter. Rapid notes-list activations
cannot stack capture destinations.

The capture view model consumes that entry request once using SavedStateHandle,
before hardware/permission checks. A staged image, busy operation or restoration
error takes precedence. Recomposition, recreation, Settings return, denial and
choosing another source/image cannot replay the request. Explicit camera actions
share the same hardware/permission path; permission is rechecked on resume
without requesting it again. Permission loss during busy camera work is reconciled
once idle, preserving a staged preview or the operation's error and allowing an
explicit retry. Denial, missing hardware or an unavailable camera
leave visible errors and the gallery/file alternatives usable.

Navigation carries an internal job/source ID, not image bytes or an external
filesystem path. Staging and lifecycle state retain that identity across retries.
Cancellation first persists a secret-free tombstone outside the image staging
directory, before waiting for a native kernel to finish. Restored jobs and cached
retries honour that marker even if image cleanup fails. If marker persistence
fails, durable selection invalidation prevents restoration; inability to persist
either is reported explicitly rather than claiming cancellation succeeded.
Cancelled or superseded work must not publish late notes.

Settings separate provider selection from Azure endpoint/key configuration. The
key is encrypted with Android Keystore-backed AES-GCM, never stored as a plaintext
preference or included in note JSON. Old Azure OpenAI settings are not migrated to
Document Intelligence. Backup rules exclude private notes, sources and secrets.

Azure submits the prepared image with its true MIME type and polls a bounded
asynchronous operation. Polling is restricted to the configured trusted HTTPS
origin; redirects must not leak the API key. The connection test uses a non-image
resource operation and states its limits. No font-style/Layout add-ons or cloud
colour computation are requested.

## Errors and verification boundaries

`RecognitionError` distinguishes configuration, unsupported device, missing model,
network, authentication, rate limiting and malformed service responses. No-text
results create zero notes and receive a visible outcome. Only unreliable colour
has the deliberate black fallback; OCR, image, storage and cancellation failures
must not be disguised as successful colour fallback.

JVM tests exercise contracts, old JSON, Azure mock responses, paragraph/colour
fixtures and storage failure/recovery. Android device checks remain necessary for
native inference and page sizes, chooser grants, EXIF decoding, Keystore,
lifecycle and camera behavior. Model download size is not measured APK size, and
passing synthetic tests is not a cursive-recognition quality claim.

The app's `verifyDebugRecognitionPackage` and `verifyReleaseRecognitionPackage`
tasks inspect the installable APKs, not just linker outputs. They prevent a
successfully linked JNI wrapper from shipping
without its required Paddle runtime or model assets. The native module explicitly
packages its pinned runtime through the `native` JNI-library source directory.

Android acceptance runs use isolated directories and Keystore aliases, with a
test application that does not initialize production settings or migrations.
The separate-UID picker/provider fixture is framework-only Java: dependencies
shared with the target app are not available when the test APK starts its own
process. This preserves actual cross-UID grants without relying on shared
Kotlin/AndroidX classes or replacing provider access with a mock.

The complete offline pipeline is exercised separately in
`NativePipelineAcceptanceTest`: a generated two-paragraph, two-colour photograph
passes through real import, bundled Paddle, paragraph/colour processing and
file-backed persistence. Emulator results validate that path, not real journal
accuracy, physical-camera capture or 16 KB device behavior.

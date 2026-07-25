# FPInk

Capture handwritten fountain-pen journal pages, transcribe them with Azure OpenAI vision, and store them as portable local notes.

**Phase 0 MVP** — Android only, local storage, no cloud sync.

## Prerequisites

- Android Studio (or IntelliJ with Android plugin)
- JDK 17
- Android SDK with:
  - Platform API 36
  - Build Tools 36.0.0
- A physical Android device with a camera (minSdk 26)
- An Azure OpenAI resource with a vision-capable model deployed (e.g., GPT-4o)

## Getting Started

1. Clone this repository
2. Open in Android Studio
3. Sync Gradle
4. Build and install: `./gradlew :app:installDebug`
5. On first launch, go to **Settings** and configure:
   - **Endpoint**: Your Azure OpenAI endpoint (e.g., `https://my-resource.openai.azure.com`)
   - **Deployment**: Your model deployment name (e.g., `gpt-4o`)
   - **API Version**: The API version (e.g., `2024-12-01-preview`)
   - **API Key**: Your Azure OpenAI API key
6. Return to the notes list and tap the capture button to photograph your first journal page

## Project Structure

```
:core:model    — Pure Kotlin. Data classes + kotlinx.serialization models.
:core:ai       — Pure Kotlin. Ktor client, Azure OpenAI request/response, prompt, parsing.
:core:storage  — Pure Kotlin. Note repository over an abstract FileStore interface.
:app           — Android. Compose UI, CameraX, DataStore, AndroidFileStore implementation.
```

The `:core:*` modules have **zero Android dependencies** — they can be promoted to KMP `commonMain` source sets without a rewrite.

## Architecture

- **UI**: Jetpack Compose + Material 3, single-Activity, Compose Navigation
- **Async**: Coroutines + Flow
- **HTTP**: Ktor client (OkHttp engine)
- **JSON**: kotlinx.serialization
- **Camera**: CameraX
- **Images**: Coil 3
- **Settings**: Jetpack DataStore (Preferences)
- **DI**: Manual constructor injection via AppContainer (no Hilt/Koin)
- **Storage**: Flat JSON files (`notes/{id}.json`) + archived photos (`images/{id}.jpg`)

## Screens

1. **Notes List** — Browse captured notes with ink color swatches and dates
2. **Capture** — CameraX preview with permission handling and confirm/retake
3. **Processing** — AI transcription with progress, error handling, and retry
4. **Note Detail** — Edit transcription, view metadata (ink color, confidence), view source photo
5. **Settings** — Configure Azure OpenAI connection

## Security

- No secrets in source code
- API key is entered by the user at runtime and stored in encrypted DataStore
- Phase 0 uses direct API key entry (dev-only); Phase 1 will replace with Entra auth + Key Vault

## Known Phase 0 Limitations

- No cloud sync — notes are local to the device
- No authentication
- No note linking or tagging (fields reserved for Phase 1 Zettelkasten features)
- No full-text search
- No Room/SQLite index — note listing reads the directory each time (fine for MVP scale)
- No offline mode — requires network for AI transcription
- No image compression or optimization
- Settings "Test Connection" is a stub (validates non-empty fields only)

## KMP Migration Path

The multi-module structure is designed for Kotlin Multiplatform migration:

1. `:core:model`, `:core:ai`, `:core:storage` are pure Kotlin/JVM with no Android dependencies
2. Convert these to KMP modules with `commonMain` source sets (no code changes needed)
3. `FileStore` interface enables platform-specific implementations (Android filesDir, iOS Documents, Desktop filesystem)
4. Ktor client already supports all KMP targets
5. kotlinx.serialization and kotlinx.datetime are KMP-ready
6. Only `:app` contains Android-specific code (Compose UI, CameraX, DataStore)

## Testing

```bash
# Run all unit tests
./gradlew test

# Core module tests
./gradlew :core:model:test    # IdGenerator format tests
./gradlew :core:ai:test        # Azure response parser tests (happy path, malformed JSON, HTTP 401, 429)
./gradlew :core:storage:test   # NoteRepository CRUD tests
```

## License

[Add your license here]

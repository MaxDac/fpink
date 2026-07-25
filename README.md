# FP-Ink

Android app that turns a photo of a handwritten fountain-pen journal page into a structured note with ink metadata.

> **Microsoft Global Hackathon 2026** — internal, non-production, Microsoft IP.
> This project is not for external distribution.
>
> Innovation Studio project page: <INNOVATION_STUDIO_URL>

---

## Screenshots

<!-- Replace with real screenshots after first demo -->
| Capture | Processing | Note Detail |
|---------|-----------|-------------|
| ![Capture](docs/screenshots/capture.png) | ![Processing](docs/screenshots/processing.png) | ![Detail](docs/screenshots/detail.png) |

## Prerequisites

- Android Studio (stable channel)
- JDK 17
- A physical Android device with USB debugging enabled (minSdk 26)
- An Azure OpenAI resource with a vision-capable deployment (`gpt-4o` or `gpt-4.1`)

## Configuration

Endpoint, deployment name, API version, and API key are entered in the app's
**Settings** screen at runtime and stored in Jetpack DataStore on-device. They are
**never** committed to source control.

> ⚠️ **Phase 0 dev-only pattern.** Direct API-key entry will be replaced by
> Entra ID authentication and Azure Key Vault in Phase 1.

## Build & Run

```bash
# Debug build
./gradlew assembleDebug

# Run unit tests
./gradlew test

# Install to connected device
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Architecture

### Modules

| Module | Purpose | Android imports? |
|--------|---------|:---------------:|
| `:core:model` | Data classes, serialization models | ❌ |
| `:core:ai` | Ktor-based Azure OpenAI client, prompt templates, response parsing | ❌ |
| `:core:storage` | `NoteRepository` over an abstract `FileStore` interface | ❌ |
| `:app` | Compose UI, CameraX, DataStore, `AndroidFileStore` | ✅ |

**Rule:** `:core:*` modules must contain **zero** `android.*` / `androidx.*` imports.
This keeps them promotable to Kotlin Multiplatform `commonMain` source sets without
a rewrite, enabling a future iOS target.

### Data Flow

```
CameraX capture → :core:ai (Azure OpenAI vision) → :core:storage → local JSON
```

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the full module diagram, Note
schema, and error hierarchy.

## Phase 0 Scope

**In scope:** capture, AI transcription, ink colour detection, local JSON storage,
settings screen, note list and detail views.

**Explicit non-goals (Phase 0):**
- No cloud sync
- No authentication / Entra ID
- No note linking or tagging (schema fields reserved for Phase 1)
- No iOS target
- No sheen / shimmer detection
- No nib-profile inference

## Roadmap

| Phase | Focus |
|-------|-------|
| **Phase 1** | Zettelkasten scaffolding — note linking, tags, search, Entra auth, Key Vault |
| **Phase 2** | Richer ink metadata, on-device inference, iOS via Kotlin Multiplatform |

## Known Limitations

- Notes are local to the device — no sync
- No offline mode — AI transcription requires network
- No full-text search
- Note listing reads the directory each time (no Room/SQLite index — fine for MVP scale)
- No image compression or optimisation
- Settings "Test Connection" validates non-empty fields only (no live endpoint check)
- Ink colour detection relies on the vision model's best guess; no colour-calibration

## Security

See [`SECURITY.md`](SECURITY.md). No secrets are stored in source control. API
credentials live only in on-device DataStore at runtime.


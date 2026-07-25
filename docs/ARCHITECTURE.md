# Architecture

## Module Diagram

```
┌─────────────────────────────────────────────────┐
│                    :app                         │
│  Compose UI · CameraX · DataStore · Navigation  │
│  AndroidFileStore · AppContainer (DI)           │
└────────┬──────────┬────────────────┬────────────┘
         │          │                │
         ▼          ▼                ▼
   ┌──────────┐ ┌──────────┐ ┌─────────────┐
   │:core:ai  │ │:core:    │ │:core:       │
   │          │ │ model    │ │ storage     │
   │Ktor      │ │Note      │ │NoteRepo     │
   │Azure AOAI│ │IdGen     │ │FileStore    │
   │Prompts   │ │Instant   │ │(interface)  │
   └────┬─────┘ └──────────┘ └──────┬──────┘
        │                           │
        └───── pure Kotlin/JVM ─────┘
              (no Android imports)
```

## Data Flow

```
Camera (CameraX)
  │
  ▼
JPEG bytes ──► :core:ai ──► Azure OpenAI (vision)
                  │
                  ▼
             PageAnalysis { text, inkColorHex, inkColorName, confidence, modelNotes }
                  │
                  ▼
             :core:storage (NoteRepository)
                  │
                  ├── notes/{id}.json   (Note serialised via kotlinx.serialization)
                  └── images/{id}.jpg   (archived source photo)
```

## Note Schema

```kotlin
data class Note(
    val id: String,              // ULID-like, e.g. "20260725-a1b2c3"
    val capturedAt: Instant,
    val imagePath: String,       // relative: "images/{id}.jpg"
    val text: String,            // transcribed handwriting
    val inkColorHex: String?,    // e.g. "#1A3C5E"
    val inkColorName: String?,   // e.g. "Pilot Iroshizuku Kon-peki"
    val confidence: Float?,      // 0.0–1.0, model self-reported
    val modelNotes: String?,     // free-text observations from the model
    val userEdited: Boolean,     // true after manual edit
    val tags: List<String>,      // reserved for Phase 1
    val links: List<String>,     // reserved for Phase 1
)
```

## AiError Hierarchy

```
AiError (sealed class, extends Exception)
├── NetworkError    — connection failures, timeouts
├── AuthError       — HTTP 401 / invalid API key
├── RateLimited     — HTTP 429
├── MalformedResponse — JSON parse failures from the model
└── Unknown         — anything else
```

All errors carry a human-readable `message`. `NetworkError`, `MalformedResponse`, and
`Unknown` also carry the original `cause` throwable for logging.

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| No Hilt / Koin | Manual DI via `AppContainer` is sufficient at this scale and avoids annotation-processor overhead |
| Flat JSON files, not Room | Simpler, portable, no schema migrations; sufficient for MVP note counts |
| Abstract `FileStore` interface | Decouples `:core:storage` from Android `filesDir`; enables KMP and test doubles |
| `:core:*` = pure Kotlin | Keeps the KMP migration path open without rewriting business logic |

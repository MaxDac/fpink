# Security Policy

## Reporting a Vulnerability

If you discover a security vulnerability in this project, please report it
responsibly:

- **Email:** [secure@microsoft.com](mailto:secure@microsoft.com)
- **MSRC:** [Microsoft Security Response Center](https://msrc.microsoft.com)

Do not open a public issue for security vulnerabilities.

## Scope

FP-Ink is a **non-production hackathon prototype** (Microsoft Global Hackathon 2026).
It does not process production data or customer data. There is no hosted service
component — the app runs locally on a developer's Android device and communicates
directly with the developer's own Azure Document Intelligence resource only when
the online provider is explicitly selected.

## Data and credentials

- PaddleOCR is the default recognition provider. Offline recognition has no
  application-managed model downloads, cloud requests or automatic cloud fallback.
- Azure Read receives the confirmed source image using the configured resource's
  HTTPS endpoint and API key. Text layout returns to the app; paragraph colour
  processing and note storage remain local. Azure's own service/data-retention
  terms still apply to uploaded images.
- Azure keys are encrypted with Android Keystore-backed AES-GCM before persistence.
  Provider settings are separate from legacy Azure OpenAI configuration; old
  resource credentials are not automatically reused.
- Keys must never enter logs, exception text, saved instance state, note records,
  source control, screenshots or test fixtures. Connection checks do not upload
  journal images.
- Async operation polling is restricted to the configured trusted HTTPS origin.
  Redirects must not forward the subscription key to another host.
- Notes, source images, imports and credentials remain in app-private storage and
  are excluded from automatic backup. There is no cloud note-sync component.
- Gallery import reads a bounded content stream into private storage. It does not
  infer file paths from external URIs or request broad storage permissions.
  Camera access is optional and independent of gallery access.

Device encryption, screen-lock strength, compromised devices and Azure resource
access remain outside the app's guarantees. Keystore protection is not a claim
that an unlocked/compromised device cannot expose an in-use key.

## Build provenance and distribution

Native libraries, detector/recognizer assets and the matching dictionary require
pinned sources, checksums and their accompanying licences. Do not package optional
proprietary accelerator libraries or silently fetch replacement models at runtime.
The dependency licences do not override this application's internal
Microsoft-IP/non-distribution restriction or establish F-Droid acceptance.

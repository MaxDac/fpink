# Contributing to FP-Ink

## Branch Naming

Use a prefix that matches the type of change:

- `feat/` — new feature
- `fix/` — bug fix
- `chore/` — build, CI, or tooling changes

## Commits

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
feat: add note search screen
fix: handle 429 rate-limit in AzureOpenAiClient
chore: bump Kotlin to 2.2
```

## Pull Requests

- Target `main`
- `./gradlew build && ./gradlew test` must pass before requesting review

### Review Checklist

- [ ] No secrets committed (API keys, endpoints, tokens, `.jks`, `.env`)
- [ ] `:core:*` modules contain no `android.*` / `androidx.*` imports
- [ ] Unit tests pass (`./gradlew test`)
- [ ] Commit messages follow Conventional Commits

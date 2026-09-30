## What Changed

Briefly describe the change.

## Change Type

- [ ] Bug fix
- [ ] Feature
- [ ] Documentation
- [ ] Tests / refactoring

## Verification

- [ ] `./gradlew build`
- [ ] `./gradlew integrationTest` (Docker required)
- [ ] Not run, reason:

## Read-only safety

- [ ] The change calls only Loki's read endpoints (no push, delete, `tail`, `/config` or arbitrary URLs)
- [ ] URLs, credentials and tenants stay out of tool responses and diagnostics; nothing new is written to stdout
- [ ] The PR contains no secrets, tokens, or private URLs

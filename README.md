# SentinelAI

**AI-Powered Pre-commit Security Guard for IntelliJ IDEA**

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.20-blue.svg)](https://kotlinlang.org)
[![IntelliJ Platform](https://img.shields.io/badge/IntelliJ-2025.x-orange.svg)](https://plugins.jetbrains.com)
[![Claude Haiku](https://img.shields.io/badge/AI-Claude%20Haiku-purple.svg)](https://anthropic.com)

SentinelAI is an open-source IntelliJ IDEA plugin that acts as an automated security checkpoint between your code and your remote repository. It intercepts Git commits and pushes to detect hardcoded secrets, API keys, system prompts, PII, and credentials — before they ever leave your machine.

---

## Inspiration

This project was directly inspired by the **Claude codebase leak** — a real-world incident in which internal Anthropic system prompts were inadvertently exposed. The leak demonstrated that even sophisticated engineering organizations can accidentally commit sensitive AI-related data: system prompts, internal instructions, and proprietary model configuration.

SentinelAI was built to make that class of mistake impossible at the individual developer level. One of its explicit detection categories is `SYSTEM_PROMPT` — hardcoded LLM instructions embedded in source code — alongside the usual suspects like API keys and database credentials.

---

## What it Does (MVP)

The MVP focuses exclusively on **Leak Shield** — detecting and blocking sensitive data from entering your repository.

```
Git Commit  →  Level 1 (regex + PSI, ~0ms)  →  block or pass
                                                       ↓
                                          Level 2 (Claude Haiku, ~1s, async)
                                                       ↓
Git Push    →  gate: clean? blocked? still running?  →  push or review
```

On every commit, the plugin runs two scanners in sequence:

**Level 1 - Instant Scanner** runs synchronously before the commit completes. It uses regex patterns and IntelliJ PSI/AST analysis to catch obvious hardcoded secrets. If it finds a critical issue, it blocks the commit immediately and shows a dialog. Zero network calls, zero latency.

**Level 2 - AI Scanner** launches asynchronously in the background. Only diffs from `MEDIUM`-and-above risk files are sent to Claude Haiku — never full file contents. By the time you hit "Push", the analysis is almost always already done. If it's still running, a progress dialog appears with a configurable timeout and a "Push Anyway" escape hatch.

---

## Detection Categories

| Category | Examples |
|---|---|
| `API_KEY` | OpenAI keys, GitHub tokens, AWS credentials, Google API keys |
| `SYSTEM_PROMPT` | Hardcoded LLM instructions and AI system prompts |
| `DB_CREDENTIAL` | JDBC connection strings with embedded passwords |
| `PRIVATE_KEY` | PEM files, certificates, cryptographic keys |
| `PII` | Emails, passwords, phone numbers in logs |

---

## Risk Classification

Every file in a commit is classified before any scanning runs:

| Level | Files | Scanners |
|---|---|---|
| `CRITICAL` | `.env`, `*.pem`, `*.key`, `.idea/dataSources.xml`, `**/secrets/**` | Level 1 + Level 2 AI |
| `HIGH` | `application.yml`, `application-prod.yml`, `Dockerfile`, `docker-compose.yml`, `**/terraform/**` | Level 1 + Level 2 AI |
| `MEDIUM` | `*Service*.kt`, `*Auth*.kt`, `*Security*.kt`, `*Controller*.kt` | Level 1 only |
| `LOW` | Everything else | Skipped |

Files listed in `.gitignore` that somehow appear in a diff are automatically escalated to `CRITICAL` — if Git was told to ignore it, it should never be committed.

---

## Roadmap

**Phase 1 — MVP Leak Shield** *(current)*
- [x] Gradle IntelliJ plugin project
- [x] Risk Map Engine
- [x] Gitignore awareness
- [x] Level 1 regex
- [x] CheckinHandler (commit interception)
- [x] Block and result dialogs

**Phase 2 — AI Integration** *(current)*
- [x] Claude Haiku HTTP client
- [x] API key via environment variable only
- [x] `never_send_to_cloud` enforcement
- [x] Prompt builder (diff-only, added lines only)
- [x] Async execution with coroutines
- [x] Push gate
- [x] "Push anyway" override
- [x] Timeout handling with configurable behavior
- [x] Exponential backoff with retry

**Phase 3 — Polish & Community** *(planned)*
- [ ] Level 1 PSI along regex
- [ ] Learning mode to reduce false positives
- [ ] Custom pattern definitions in `.sentinel.yml`
- [ ] Per-session cost tracking
- [ ] Provider abstraction (swap Claude for other LLMs)
- [ ] Skip history log
- [ ] Full documentation and CI pipeline
- [ ] JetBrains Marketplace submission

---

## Tech Stack

- IntelliJ Platform SDK 2025.x
- Kotlin 2.1.20 / Java 21
- Gradle with Kotlin DSL
- Anthropic Messages API (plain Java `HttpClient`, no external SDK)
- IntelliJ PSI (Program Structure Interface)
- SnakeYAML
- kotlinx.serialization
- IntelliJ VCS API / Git4Idea
- GitHub Actions

---

## Contributing

Contributions are welcome. The most valuable contributions right now are additional test cases for the Level 1 scanner — both true positives (real secrets that should be caught) and true negatives (safe patterns that should not trigger false alarms). Quality is measured by the false positive and false negative rate in the test suite.

Please open an issue before submitting a large PR.

---

## License

MIT License — see [LICENSE](LICENSE) for details.

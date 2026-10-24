# mindverse

Android second-brain app (native Kotlin + Compose, sideload-first, local-first).
Canonical plan: `.scratch/mindverse-v1/spec.md` — all product/architecture decisions live there; read it before working any ticket.

## Build status

Next work: Milestone 1 tickets in `.scratch/mindverse-v1/issues/` (01–10), worked blockers-first (01 → 02,03 → 04 → 05,06 → 07 → 08,09,10). Set ticket `Status:` as you claim/resolve per `docs/agents/triage-labels.md`.

## Rules

- When a task involves asking the user questions (grilling, decision forks, clarifications), always present them via the `question` tool so they're interactive — never as plain-text numbered lists.
- Commit messages follow Conventional Commits: `feat:`, `fix:`, `chore:`, `docs:`, `refactor:`, `test:`, `build:`.
- Versioning starts at `0.0.0`: `fix:` bumps the patch automatically; `feat:` proposes a minor bump which requires the user's yes first (ask via `question`, bump only on confirmation); never bump the major version — the user does that manually.
- Vocabulary is normative per `GLOSSARY.md` (Capture / Memory / Pin / Collection / Trigger / Import / Ask / Entity).
- ADRs in `docs/adr/` are hard decisions; flag conflicts, don't silently override.
- Raw AI calls are FORBIDDEN outside the egress chokepoint module (ADR-0002) — once it exists, every outbound call must route through it.
- `docs/private/` and `*.apk` are gitignored local-only material; never commit them, never re-link vendor identities in tracked files.

## Agent skills

### Issue tracker

Issues and specs are tracked as local markdown files under `.scratch/<feature>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

The default five-role vocabulary (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `GLOSSARY.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.

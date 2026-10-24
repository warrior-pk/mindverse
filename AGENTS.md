# mindverse

Android second-brain app (native Kotlin + Compose, sideload-first, local-first).
Canonical plan: `.scratch/mindverse-v1/spec.md` — all product/architecture decisions live there; read it before working any ticket.

## Build status

Next work: Milestone 1 tickets in `.scratch/mindverse-v1/issues/` (01–09), worked blockers-first (01 → 02,03 → 04 → 05,06 → 07 → 08,09). Set ticket `Status:` as you claim/resolve per `docs/agents/triage-labels.md`.

## Rules

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

# 03 — Egress chokepoint + provider adapter (Gemini + OpenAI-compatible)

Status: ready-for-agent
ADR: docs/adr/0002-egress-chokepoint-invariant.md
Spec: .scratch/mindverse-v1/spec.md (Egress & Privacy screen rows)

## Body

Implement the Egress Policy module and the LLM adapter:

- Single chokepoint API every outbound AI call must use: input = requestId + reason code + payload type (chat/vision/embed), applies PII masking regex families (phone/email/ID/generic card/OTP-like numbers) + logs `{captureId?, feature, payload classes, provider, bytes-out, at}` to a Room `egress_log` table.
- Per-feature toggles (e.g. voice audio egress) checked at the chokepoint; a denied toggle short-circuits with a typed failure the feature can degrade on.
- Provider-neutral interface: Chat(stream, structured-output), Vision(structured-output), Embed (Embed stays a **local no-op stub here**; real one is issue 04/07).
- Implementations: Google Gemini (AI Studio key) and OpenAI-compatible HTTP with custom base URL (OpenRouter/DeepSeek/Ollama/LM Studio). Keys read from the Keystore-encrypted store.
- Privacy screen UI: chronological egress log grouped by day, per-feature toggles. (This is the only UI in this issue.)

## Accepted when
Every call site compiles only via the chokepoint (make raw provider types internal), masking unit-tested on a snapshot corpus, log rows written on both success and failure, Privacy screen renders live from `egress_log`.

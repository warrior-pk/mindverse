# Egress chokepoint as a non-negotiable invariant

Every outbound AI call (LLM chat/vision, embedding, any future cloud destination) must pass through a single Egress Policy module in the app: it applies PII masking, attaches a per-request reason code, and writes a user-visible log entry. We decided this from day one — modeled on a commercial OEM memory app whose decompiled code routes every chat request through a data-masking layer before sending and gates sends with a security check (local-only teardown analysis) — because a privacy chokepoint is retrofittable only at ruinous cost once many call sites exist. Consequences: features must degrade gracefully when the user disables a toggle (e.g. voice audio egress), and model accuracy on masked content is accepted as a trade for the invariant.

Considered options: chokepoint without masking/logging (rejected — invisible invariant invites drift), no chokepoint until a backend exists (rejected — too late then).

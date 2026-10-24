# 07 — Ask mode: hybrid retrieval + streaming grounded chat with citations

Status: ready-for-agent
Spec: .scratch/mindverse-v1/spec.md (Ask row)

## Body

Implement Ask end-to-end:

- **Retrieval**: hybrid scoring = FTS5 BM25 over (title+summary+tags+extracted text) + sqlite-vec cosine over the on-device embedding, blended with recency decay; metadata filters (category, time range, source app) from the query language parsed by a small structured-intent pass (no LLM rewriting/agent in v1).
- **Grounding**: top-K Memory cards context, prompt strictly forbids answering from model knowledge; UI shows source chips per streaming block.
- **Streaming**: SSE-style streaming through the Egress chokepoint; citation chips tappable → deep-link to Memory detail (issue 06).
- Ask runs even keyless with degraded "paragraph keyword fallback" (pure retrieval, no generation) so the_Milestone-1 app is never unusable without a key (R1-2 hybrid invariant).
- Query embedding uses the same local embed path as issue 04 (no cloud embed; consistency matters, results differ otherwise).

## Accepted when
Golden queries (fixture DB) return the intended Memory in top-3 for paraphrase phrasing, answer streams with chips per source, keyless mode still returns memory-first results.

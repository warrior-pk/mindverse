# 07 — Ask mode: hybrid retrieval + streaming grounded chat with citations

Status: resolved
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

## Comments

- 2026-10-25 (implemented): Ask lives in `core/ask` (retrieval + grounding) and
  `ui/ask` (streaming conversation).
  - **Retrieval** (`HybridRetriever`): FTS5 BM25 (phrase + token-OR passes) + sqlite-vec
    cosine over the ticket-04 embedding, blended 0.55/0.35 with a 30-day recency decay
    (0.10). Vector hits need a cosine-similarity floor (KNN always returns *some* rows).
    Metadata filters (category / time range / source app) narrow the pool before scoring;
    filter-only queries rank the filtered pool, plain no-match queries stay empty.
  - **Query intent** (`AskIntentParser`): deterministic rules — `category:x`, `app:x`,
    "from <app>", "today / yesterday / this week / this month". No LLM rewriting (spec).
  - **Grounding**: strictly grounded prompt (numbered `[n]` Memories, "never answer from
    general knowledge"); `[n]` markers resolve to per-block tappable citation chips that
    deep-link to Memory detail. Streaming goes through the egress chokepoint
    (`EgressFeature.ASK`).
  - **Keyless fallback** (R1-2): MissingKey / toggle-off / early failure degrades to a
    cited local paragraph per retrieved Memory; the UI labels it as answered locally.
  - Query embedding reuses the exact `EmbeddingEngine` DI instance (consistency).
  - Tests: `HybridRetrieverTest` (golden paraphrase queries hit the intended Memory top-3
    on a fixture DB; filters narrow; no-match empty), `AskServiceTest` (citation blocks,
    grounded prompt contents, keyless fallback, no-AI-call short-circuit),
    `AskIntentParserTest`. Lint + tests green on both flavors.

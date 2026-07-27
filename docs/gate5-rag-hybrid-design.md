# Gate 5 — Hybrid Dense + Lexical (BM25/FTS) RAG Retrieval

**Issue:** #784 (depends on #783 — recall@k live harness, done). **Priority:** low (enhancement).
**Status:** design.

## 1. Goal

Add a lexical retrieval path (PostgreSQL full-text search) alongside the existing dense
(vector) path and fuse the two with Reciprocal Rank Fusion (RRF), so the RAG corpus is
searchable by both semantic similarity and exact term/keyword match. Exact identifiers,
domain jargon, permission codes, and rare tokens that dense embeddings under-weight should
become reliably retrievable, without regressing the dense recall the #783 harness now gates.

Non-goal: replacing dense retrieval, introducing an external search engine (Elasticsearch/
OpenSearch), or changing the embedding model. This stays inside Postgres.

## 2. Current state (as built)

Retrieval is **pure dense**:

- Store: `mcp_document_embedding` — `id uuid`, `embedding vector(768)`, `content text`,
  `metadata jsonb`, `created_at`. Dense index: `ivfflat (embedding vector_cosine_ops)`.
  `rag_scope` is a key inside `metadata` (not a column).
- `ScopedContentRetrieverFactory` (`@Profile("alpha")`) builds a dense
  `QueryDocumentRetriever` via Spring AI `PgVectorStore.similaritySearch`, scope-filtered
  with `FilterExpressionBuilder.eq("rag_scope", …)`, `topK` + `similarityThreshold`.
- `QueryDocumentRetriever` is a functional interface: `List<Document> retrieve(String)`.
- `HybridContentRetriever` today is a **query-expansion** hybrid: it merges results from
  several retrievers (dense + query-expanded dense via `QueryExpansionContentRetriever`) and
  de-dupes by normalized content string — there is no scoring or lexical source.
- `RerankedContentRetriever` re-ranks already-retrieved candidates by query/​content token
  overlap + phrase boost + source rank. This is a *re-rank signal*, not a lexical *retrieval*
  path (it can only reorder what dense already returned).
- `ResilientContentRetriever` wraps the chain and degrades to empty context on failure.

No `tsvector` / `ts_rank` / `websearch_to_tsquery` exists in `pos-mcp-server/src/main`.

## 3. Design

### 3.1 Schema — `tsvector` generated column + GIN index (Postgres-only migration)

Add a **stored generated column** so the lexical vector is maintained automatically on every
insert/update — no application code needs to keep it in sync (important: documents are written
by Spring AI `PgVectorStore.add()`, which we don't control).

```sql
-- V28__mcp_document_fts.sql  (Postgres migration; H2 test schema is unaffected)
ALTER TABLE mcp_document_embedding
  ADD COLUMN content_tsv tsvector
  GENERATED ALWAYS AS (to_tsvector('english', coalesce(content, ''))) STORED;

CREATE INDEX mcp_doc_content_tsv_idx ON mcp_document_embedding USING gin (content_tsv);
```

Notes:
- `english` config is the default; revisit if the corpus is multilingual (out of scope now).
- Generated-STORED requires Postgres 12+ (alpha is PG16 — fine).
- No backfill step needed: a `GENERATED ALWAYS … STORED` column is computed for existing rows
  at `ADD COLUMN` time.
- The H2 dev/test schema (`db/h2-migration`) is a separate migration set and does **not** get
  this column; the lexical retriever is `@Profile("alpha")`/prod-only (§3.4), so H2 tests never
  hit FTS SQL.

### 3.2 Lexical retriever — `LexicalDocumentRetriever`

A new `QueryDocumentRetriever` implementation backed by `JdbcTemplate` (mirrors the
`ToolMetadataRepositoryImpl` JDBC style; PgVectorStore has no FTS API), scope-filtered exactly
like the dense path:

```sql
SELECT id, content, metadata,
       ts_rank_cd(content_tsv, websearch_to_tsquery('english', ?)) AS rank
FROM   mcp_document_embedding
WHERE  content_tsv @@ websearch_to_tsquery('english', ?)
  AND  metadata ->> 'rag_scope' = ?
ORDER  BY rank DESC
LIMIT  ?;
```

- `websearch_to_tsquery` accepts free-text/quoted-phrase queries safely (no query-syntax
  injection surface; unparseable input yields an empty query → zero rows, never an error).
- Rows map to Spring AI `Document(id, content, metadata-map)` so downstream stages
  (fusion, re-rank, prompt assembly) treat lexical and dense hits identically.
- Empty/blank query, or a query that tokenizes to nothing, returns `List.of()` (no lexical
  contribution) rather than throwing.
- Same `rag_scope` normalization (`RagScope.normalize`) as the dense factory, so scope
  semantics stay identical across paths.

### 3.3 Fusion — Reciprocal Rank Fusion in `HybridContentRetriever`

`HybridContentRetriever` gains a selectable fusion mode. With the flag **off** it keeps the
existing insertion-order (putIfAbsent) merge unchanged, so the dense-only path is byte-for-byte
identical to today; with the flag **on** (lexical source present) it fuses with **RRF**. Dedup
is by document **id** in both modes (falling back to normalized content). RRF scoring:

```
score(d) = Σ_retrievers  weight_i / (k + rank_i(d))
```

- `k` (rank constant) default **60** (the standard RRF default); configurable.
- `rank_i(d)` is the 1-based position of document `d` in retriever *i*'s result list; a
  retriever that doesn't return `d` contributes 0 for `d`.
- Dedup key: document **id** when present (fall back to normalized content) — more correct
  than today's content-string key, and it lets the same doc surfaced by both paths accumulate
  score from both.
- Optional per-source `weight_i` (default 1.0 each) so dense vs lexical can be tuned from
  config without code changes.
- Output: documents sorted by fused score, truncated to `maxMergedResults`.

Pipeline (unchanged shape, RRF swapped in for the naive merge; existing re-rank kept per AC):

```
[ dense(scope), lexical(scope), (optional) query-expanded-dense ]
        │  each returns a ranked List<Document>
        ▼
HybridContentRetriever  ── RRF fuse + dedup-by-id ──►  fused ranked list
        ▼
RerankedContentRetriever  ── token-overlap/phrase re-rank ──►  top-K
        ▼
ResilientContentRetriever  ── failure → empty context (unchanged)
```

RRF is a deliberate choice over weighted-score fusion because dense cosine scores and
`ts_rank_cd` values are on incomparable scales; RRF fuses on **rank**, not raw score, so no
score normalization/calibration is needed.

### 3.4 Wiring, config, profiles

- Extend `ScopedContentRetrieverFactory` (or add a sibling `LexicalContentRetrieverFactory`,
  both `@Profile("alpha")`) to build the scope-filtered `LexicalDocumentRetriever` from an
  injected `JdbcTemplate`. Keep the dense builder as-is.
- Retriever assembly (`SessionAgentManager` / `StreamingSessionAgentManager`, which call the
  factory today) composes `[dense, lexical]` into the RRF `HybridContentRetriever`.
- New config under `mcp.rag.hybrid` (bind via `McpServerProperties` or a dedicated record):
  | property | default | meaning |
  |---|---|---|
  | `lexical-enabled` | `false` | feature flag — off ships the lexical path dormant; flip on after validation |
  | `rrf-k` | `60` | RRF rank constant |
  | `lexical-max-results` | `maxResults` | topK for the FTS query |
  | `dense-weight` / `lexical-weight` | `1.0` / `1.0` | per-source RRF weights |
- **Flag default off** so the migration + code can merge and deploy decoupled from the
  behavior change; enabling is a one-line config flip on alpha, reversible instantly.

## 4. Validation (via the #783 harness)

The #783 gate (`scripts/eval_live.py`, now scheduled via `eval-cron.sh`) already scores
`hit@5 / MRR / recall@k` and flags forbidden-doc leaks against the live corpus.

1. **Baseline:** run the harness with `lexical-enabled=false` (pure dense) → record current
   metrics (recall@k was ~0.96 at `RAG_MIN_SCORE=0.55` per #783/#1119).
2. **Hybrid:** run with `lexical-enabled=true` → require **no regression** on the existing
   metrics and (target) improvement on keyword/identifier-heavy queries.
3. **Lexical-sensitive fixtures:** add eval queries that dense currently misses — exact
   permission codes (`crm:party:view`), rare identifiers, verbatim phrases — to
   `scripts/fixtures` so the harness demonstrates the lexical path's value and guards it going
   forward.
4. Tune `rrf-k` / weights only if step 2 shows a regression; re-run to confirm.

Gate stays green ⇒ safe to leave `lexical-enabled=true` on alpha and close #784.

## 5. Risks & mitigations

- **Regressing dense recall.** Mitigated by RRF (additive — a doc dense ranks #1 keeps a large
  `1/(k+1)` contribution), the #783 gate as a hard check, and the feature flag for instant
  rollback.
- **FTS write overhead.** A stored generated column + GIN index adds per-insert cost; the RAG
  corpus is small and write-rare (static preload), so negligible.
- **Scope filter parity.** Lexical uses `metadata ->> 'rag_scope'` where dense uses the
  PgVectorStore metadata filter; both must normalize identically — covered by reusing
  `RagScope.normalize` and an integration check that both paths return the same scope set.
- **H2/test divergence.** FTS is Postgres-only; fusion logic is pure Java and unit-tested
  without a DB, and the lexical retriever is profile-gated off in tests.
- **Query-injection.** `websearch_to_tsquery` is parameterized and treats input as data;
  no dynamic SQL string-building of the query term.

## 6. Out of scope

Multilingual FTS configs; external search engines; learned/cross-encoder rerankers (the
existing token-overlap re-rank is retained as-is); changing the embedding model or dimensions;
re-ingesting or re-chunking the corpus.

## 7. Task breakdown

1. **Migration** `V28__mcp_document_fts.sql` — `content_tsv` generated column + GIN index (§3.1).
2. **`LexicalDocumentRetriever`** — JDBC FTS retriever, scope-filtered, `Document` mapping,
   empty-query guard (§3.2). Unit test with mocked `JdbcTemplate` (repo-test style).
3. **RRF in `HybridContentRetriever`** — rank-fusion + dedup-by-id + optional weights (§3.3).
   Pure-Java unit tests: rank fusion math, dedup, both-source accumulation, single-source
   degenerate case.
4. **Factory + wiring + config** — build the lexical retriever, compose `[dense, lexical]`
   under RRF, add `mcp.rag.hybrid.*` properties with `lexical-enabled=false` default (§3.4).
5. **Eval** — add lexical-sensitive fixtures; run the #783 harness baseline vs hybrid; record
   deltas (§4).
6. **Flip the flag on alpha** once the gate is green; close #784.

Each of 1–4 is independently reviewable; 5–6 are the validation/rollout gate.

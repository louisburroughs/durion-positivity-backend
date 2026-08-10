# Gate 5 — RAG Expansion, Permission-Aware Filtering, Hybrid Retrieval (design)

> **Status:** IMPLEMENTED + CUT OVER. Hybrid dense+lexical RRF shipped (#784, PR #1123); corpus at
> 39 docs (#1124); bge-m3 1024-dim cutover executed on alpha 2026-08-09 (#1194; HNSW via V32;
> 768 columns retired by V33, #1207); floors re-baselined (#1179 — 0.68/0.65/0.82); item-4 probe
> 7/7 after master-scope docs were included in domain-scoped retrieval (PR #1209, #1180 CLOSED).
> See the dated cutover records at the end of this doc and the Gate 5 sign-off in
> `implementation_checklist.md`. Design + historical state retained below as the record.

## Current state at design time (historical — pre-cutover)
- Embeddings: `nomic-embed-text`, `mcp.rag.dimension=768`, table `mcp_document_embedding`, ivfflat.
- Tier-2 chain (built): baseline + query-expanded dense retrievers → `HybridContentRetriever` (merge)
  → `RerankedContentRetriever` → top-5. Scope filter via `ScopedContentRetrieverFactory` on the
  `rag_scope` metadata key.
- Visibility filter: `RoleAwareMetadataFilter` matches an `allowed_roles` metadata field against the
  caller's **roles** — this is the role-only gate Gate 5 replaces.
- Preload entry: `StaticDocEntry(id, sourcePath, ragScope)` — no permission tag yet.

## G5.1 — Permission-aware visibility filter (replaces role-only)
Add `PermissionAwareMetadataFilter implements ContentRetriever` that admits a retrieved doc when:
- the doc has no `required_permissions` metadata (public / `AUTHENTICATED`), **or**
- the caller's `permissionCodes` intersect the doc's `required_permissions`.

Caller permission codes come from `RequestScopedUserContext` (the Gate 3 holder — reuse it; do not
re-derive from roles). Role may be used as a *convenience hint* for scope selection only, never as
the visibility gate. Retire `RoleAwareMetadataFilter` (rename `*_deprecated` path like Gate 2B, or
delete once the permission filter is wired) so role is not the sole gate.

Rationale: a technician with elevated admin permissions, or a manager acting in another capacity,
must retrieve by *actual permissions*, not nominal role.

## G5.2 — Document permission metadata
- Extend `StaticDocEntry` → add `@Nullable List<String> requiredPermissions` (and keep `ragScope`).
- Extend the ingestion request DTO (`POST /v1/mcp/documents`) with `requiredPermissions`.
- Persist both `rag_scope` and `required_permissions` into the embedding-store metadata at
  ingest/preload time (alongside the existing `rag_scope`). Admin/security docs carry admin/security
  permission codes; staff docs carry domain read codes; capability/glossary docs carry `AUTHENTICATED`.

## G5.3 — Hybrid dense + lexical retrieval
Exact identifiers (workorder #, SKU, VIN, invoice #, PO #, account/claim codes) under-retrieve on
pure dense ANN. Add a lexical retriever and fold it into the existing Tier-2 hybrid:
- Add a Postgres full-text column to `mcp_document_embedding` (`content_tsv tsvector` + GIN index),
  or a parallel FTS table, populated at ingest.
- `PostgresFtsContentRetriever implements ContentRetriever` runs `websearch_to_tsquery` /
  `ts_rank_cd` over the scoped, permission-eligible rows.
- Merge it into `HybridContentRetriever` (already does dense + expanded) so the rerank stage sees
  dense + lexical candidates. Merge weighting is **tuned against the #783 harness**, not by intuition.
- `bge-m3` (G5.4) also yields sparse vectors — an alternative lexical signal; pick FTS *or* bge-m3
  sparse, decide by harness recall, and record the comparison.

## G5.4 — Embedding migration 768 → 1024 (`bge-m3`)
> **Superseded (#1194):** the migration sketch below predates the V24 finding that Spring AI
> `PgVectorStore` hardcodes the `embedding` column name. The implemented mechanism and the exact
> alpha cutover steps are in [G5.4 implementation — dual-column cutover (V31, #1194)](#g54-implementation--dual-column-cutover-v31-1194)
> at the end of this document.

One-way, whole-corpus re-embed — do it once, reversibly:
1. Add `bge-m3` `EmbeddingModel` bean; add `embedding_1024 vector(1024)` column (keep the 768 column).
2. Re-embed the full corpus into `embedding_1024`; build the index (ivfflat now, **HNSW** when the
   tool/doc count grows).
3. Bump `mcp.rag.dimension` only behind a flag; retrieval reads 1024 **after** the #783 harness shows
   recall ≥ the 768 baseline.
4. Keep the 768 column until 1024 is validated; then a follow-up migration drops it.
- Rollback: flip retrieval back to the 768 column.

## G5.5 — New RAG documents (offline-authorable now)
From `nl-interface-design.md` §3.2. Each gets a deterministic id, `rag_scope`, `required_permissions`,
and a chunking choice:

| Doc | rag-scope | required_permissions | chunk |
|---|---|---|---|
| “What can I ask” capability catalog (per-role sections) | master | AUTHENTICATED | 500 |
| Cross-domain workflow playbooks (estimate→…→invoice; PO→receive→reconcile; warranty/claim) | master | AUTHENTICATED | 1500/200 |
| Glossary + identifier formats (WO#, SKU, VIN, invoice#, PO#, codes) | master | AUTHENTICATED | 500 (small) |
| Order / Pricing / Tax | order/pricing/tax | domain read codes | 1500/200 |
| Customer / Vehicle / Catalog | customer/vehicle/catalog | domain read codes | 1500/200 |
| Reporting metric definitions | reporting | reporting read | 1500/200 |
| Governance & approval-gate | admin | **admin perm** | 1500/200 |
| Observability / event-tracing | events | audit/observability perm | 1500/200 |
| Role→permission catalog matrix | master/security | **admin/security perm** | 800 |

Hygiene (Retrieval lock): every doc needs deterministic id, content hash, `rag_scope`,
`required_permissions`, documented chunking. Small chunks for glossary/id/permission docs.

## Drift guards (Gate 5 locks)
- No doc accepted without permission metadata.
- No role-only RAG filtering (permission-first; role is a hint).
- Embedding migration not piecemeal (whole-corpus, snapshot first).
- Hybrid weights tuned by the harness, not intuition.
- Admin/security docs never returned to non-admin/non-security fixtures.
- RAG docs do not substitute for missing API/tool grounding.

## Verification (live — runbook §B.8)
- Exact WO/invoice/PO/VIN/SKU/account-code/claim fixtures improve recall (dense-only vs hybrid recorded).
- Admin-only docs never returned to non-admin fixtures; permission-elevated users retrieve by permissions.
- recall@k improves or within approved threshold (#783 harness).
- Chunking validated (small for glossary/id; larger for prose).

## Implementation order
G5.5 (author docs — offline) → G5.2 (metadata + preload/ingest fields) → G5.1 (permission filter,
reuse Gate 3 holder; retire role filter) → G5.3 (FTS retriever + hybrid merge) → G5.4 (bge-m3 1024
migration, dual-column) → live §B.8 with the #783 harness.

## G5.4 implementation — dual-column cutover (V31, #1194)

Supersedes the G5.4 migration sketch above. Implemented (schema + config path only; the live
re-embed happens on alpha):

- **Schema (pg `V31__bge_m3_dual_embedding_column.sql`)**: nullable `embedding_1024 vector(1024)`
  on `mcp_document_embedding`, `mcp_tool`, and `mcp_screen_registry`; ivfflat cosine indexes on the
  first two (mirroring V2/V6); updatable view `mcp_document_embedding_1024` aliasing
  `embedding_1024 AS embedding`, because `PgVectorStore` hardcodes the `embedding` column (V24) —
  the view supports both `<=>` reads and `INSERT … ON CONFLICT (id) DO UPDATE` upserts (verified on
  pgvector/PG16). H2 mirror `V23` is a documented no-op (H2 has no pgvector). Rollback: drop the
  view, the two `_1024` indexes, and the three columns.
- **Config**: `mcp.rag.embedding-column` (`embedding` default | `embedding_1024`;
  env `MCP_RAG_EMBEDDING_COLUMN`) + `mcp.rag.dimension` (env `MCP_RAG_DIMENSION`).
  `RagEmbeddingSettings` fails startup unless column and dimension agree
  (`embedding`↔768, `embedding_1024`↔1024), so the unpopulated 1024 column can never be read by a
  half-flipped config. The knob drives: the `PgVectorStore` table/view + dimension
  (`RagConfiguration`), tool vector search (`ToolMetadataRepositoryImpl`), screen vector search
  (`ScreenRegistryRepositoryImpl`), and the startup embedding backfills
  (`ToolEmbeddingInitializer`, `ScreenEmbeddingInitializer`). `LexicalDocumentRetriever` is
  FTS-only (V28) and is unaffected.

### Alpha cutover steps (in order)

1. **Re-embed with bge-m3 into the 1024 columns** while the service keeps running on 768: pull
   `bge-m3` in Ollama, then batch-populate `mcp_document_embedding.embedding_1024` (from `content`),
   `mcp_tool.embedding_1024` (from `description`), and `mcp_screen_registry.embedding_1024` (from
   `description`). The 768 columns and all live traffic are untouched.
2. **Validate retrieval quality**: run `scripts/eval_live.py --baseline` against a scratch instance
   started with the flipped config (step 3 values) and compare hit@5 / MRR / recall to the current
   768 baseline in `pos-mcp-server/target/eval/baseline-live-python.json`. Do not proceed on a
   regression.
3. **Flip config atomically** (all three together, then restart):
   `MCP_RAG_EMBEDDING_COLUMN=embedding_1024`, `MCP_RAG_DIMENSION=1024`,
   `OLLAMA_EMBEDDING_MODEL=bge-m3`. Startup fails fast if the trio is inconsistent.
4. **Keep the 768 columns until 1024 is validated in live use** (runbook §B.8, #783 harness).
   Rollback is the reverse flip of the same three values. Only after sign-off does a follow-up
   migration drop the 768 columns/indexes and the then-unused view indirection.

### Alpha cutover record — 2026-08-09 (#1194 executed, #1179 re-baselined, #1180 re-diagnosed)

Executed on alpha, image `sha-486c132` (PR #1205: configurable similarity floors + eval column
selector; includes PR #1199). Flyway V31 verified applied before starting.

**Snapshot:** `/home/ec2-user/gate5-768-snapshot-20260809T024338Z.sql.gz` on the alpha host
(pg_dump of `mcp_document_embedding`, `mcp_tool`, `mcp_screen_registry`; 2.77 MB, verified) —
recoverable beyond the built-in reverse-flip rollback.

**Step 1 — re-embed (2026-08-09 ~02:45Z):** whole-corpus bge-m3 into `embedding_1024`:
163 document chunks (39 docs, from `content`), 529 tool rows (from `description`), 3 screen rows
(from `description`); zero NULL `embedding_1024` afterward; 690 s.

**Step 2 — validation:** first attempt (2026-08-09 early) STOPPED on the no-regression gate:
recall@k 0.8431 (768) → 0.6078 (1024) at the then-hardcoded floors — root-caused to bge-m3's
cosine-similarity scale sitting ~0.10–0.15 below nomic's (rank metrics flat-to-better: hit@5 0.76
both, MRR 0.7222 → 0.7333). Floor sweep on the 1024 path: 0.45 → 0.8235, 0.40 → 0.8627,
0.35 → 0.9216, forbidden 0 throughout. Outcome: PR #1205 made the floors configurable
(`MCP_RAG_MIN_SCORE` / `MCP_RAG_TIER2_MIN_SCORE`, defaults 0.6/0.55 unchanged).

**Step 3 — flip (2026-08-09T13:07:39Z):** five values together in `/opt/durion/alpha/.env` —
`MCP_RAG_EMBEDDING_COLUMN=embedding_1024`, `MCP_RAG_DIMENSION=1024`,
`OLLAMA_EMBEDDING_MODEL=bge-m3`, `MCP_RAG_MIN_SCORE=0.45`, `MCP_RAG_TIER2_MIN_SCORE=0.40` —
then `redeploy-backend-tag.sh sha-486c132 pos-mcp-server`. Startup consistency validation passed
(`Configured Ollama embedding model … modelName=bge-m3 dimensions=1024`); the initializers
backfilled 233 newly-discovered tool rows into `embedding_1024` with bge-m3 on the flipped write
path. Pre-flip env backed up at `/tmp/alpha.env.bak.preflip-1194` on the host.

**Post-flip defect found + fixed live — V31 ivfflat indexes were built empty (V32):** an ivfflat
index trains centroids at build time; V31 created the 1024 indexes on empty columns, and even a
post-population REINDEX left `lists=100` spreading 163 chunks at 1–2 rows per list with
`probes=1`. Live document ANN through the 1024 column returned a single candidate (first
post-flip probe: PO-number question lost its glossary grounding). Replaced both 1024 indexes
with HNSW (pgvector 0.7.2) live at ~13:25Z; migration `V32__hnsw_1024_indexes.sql` (pg) /
`V24` (H2 no-op) records the same statements. ANN verified complete afterward. The 768 ivfflat
indexes are untouched (rollback path).

**Step 2 re-validation + #1179 re-baseline (post-flip):** `eval_live.py --baseline 3`
(bge-m3/1024, eval floors mirroring production 0.40/0.45): hit@5 0.76, MRR 0.7333, recall@k
0.9216 — identical across all 3 runs (stddev 0.0). Dense-vs-hybrid: bge-m3 leaves a single
dense-miss fixture (nomic left 10 at its floors); hybrid recovers it (recovery 1.0). New floors
committed in `eval_live.py` at mean×0.89: hit@5 0.68 (unchanged) / MRR 0.65 / recall@k 0.82,
with the measured basis and the dense-only-gating decision recorded in the threshold comment
block. Final floored sanity run on the end state: PASS with margin, `rag_forbidden` 0. The eval
now follows the live `.env` (embedding column, model, floors) so the nightly gate tracks the
flipped pipeline without cron changes.

**Step 4:** 768 columns + indexes retained. Dropping them is a follow-up migration after live
sign-off; rollback = reverse flip of the five env values.

**#1180 probe (7-question item-4, blocking chat, gpt-oss:120b):** 6/7 grounded. VIN, PO-number,
pricing, tax, returns, core-charge all answer from the corpus (VIN and PO — the former lexical
rescues — are now dense hits under bge-m3). The compound invoice question still fails, but the
root cause is NOT the RRF/top-5 rerank hypothesis this issue was filed with: under bge-m3 the
`glossary.identifiers` invoice chunk ranks dense #1 (0.671) for the full compound query. The
live drop is upstream: tool selection for the question picks only `AdminFacadeTool` (the
"permission" clause dominates), `resolveRagScopeForTools` therefore resolves `ragScope=admin`,
and scoped retrieval searches admin-scope docs only — the master-scope glossary is unreachable
regardless of ranking (the #1169 all-scope behavior applies only when the scope resolves to
`master`). Secondary: when gpt-oss then answers in its thinking channel (blank content), the
answer-resolution ladder replaces even the recovered text with a screen deflection; and
`OLLAMA_CHAT_THINK` was not passed through docker-compose at all (fixed alongside this record).
Candidate fix directions (for #1180): include master-scope docs in domain-scoped retrieval
(scope filter `rag_scope IN (:scope, 'master')`), and/or ladder should prefer thinking-recovered
text over a deflection when retrieval produced context.

### 768-column retirement — #1207 (V33)

Signed off 2026-08-09/10 after the flipped pipeline held green across repeated floored evals
(hit@5 0.76 / MRR 0.7333 / recall@k 0.9216, `rag_forbidden` 0, deterministic) and the item-4 probe
reached 7/7 (PR #1209). V33 closes the dual-column window: drops the three 768 `embedding` columns
and their ivfflat indexes (V2/V6 — both were built-on-empty and only functioned by accident of
their degenerate layout), renames `embedding_1024` → `embedding` (taking over the historical index
names; HNSW since V32), and drops the `mcp_document_embedding_1024` view (PgVectorStore now
targets the base table directly). Defaults across application.yml / compose / the managers / the
eval moved to the bge-m3 pairing: model `bge-m3`, dimension 1024, floors 0.45/0.40.
`RagEmbeddingSettings` now validates the single `embedding`↔1024 pairing (kept as the
SQL-injection guard and the seam for any future dual-column migration). Recovery from here is a
re-embed from `content`/`description` — embeddings are derived data; the pre-cutover snapshot and
env backups on the alpha host are removed once V33 is verified live.

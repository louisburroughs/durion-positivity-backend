# Gate 5 — RAG Expansion, Permission-Aware Filtering, Hybrid Retrieval (design)

> **Status:** DESIGN. Improves grounding, exact-code recall, and permission-safe visibility.
> Retrieval-quality verification is live (needs pgvector + the embedding model) — runbook §B.8.
> **Note:** authoring the new RAG documents (G5.5) is the one part that is NOT live-blocked and can
> proceed offline at any time.

## Current state (grounded)
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

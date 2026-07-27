#!/usr/bin/env python3
"""Live MCP eval without the JVM: reproduces the production tool-selection SQL against the alpha
Postgres/pgvector and the local Ollama embedding model. Java-free alternative to BaselineCaptureIT
(#783) and OpenApiToolPermissionGatingIT (#779) for hosts that have Python but not a JDK.

It embeds each fixture utterance with nomic-embed-text (the same model that produced the stored
mcp_tool.embedding vectors) and runs the exact permission+workflow-gated ANN query from
ToolMetadataRepositoryImpl.findTopKByEmbeddingForPermissions:

    SELECT t.name FROM mcp_tool t
    JOIN mcp_tool_workflow tw ON t.id = tw.tool_id
    JOIN mcp_workflow_state ws ON tw.workflow_state_id = ws.id
    WHERE t.enabled AND t.source <> 'openapi' AND t.embedding IS NOT NULL
      AND ws.name = :workflow
      AND t.id IN (SELECT tool_id FROM mcp_tool_permission WHERE permission_code = ANY(:perms))
    ORDER BY t.embedding <=> :query::vector, t.id
    LIMIT :k

It also captures RAG recall@k (#783 AC3): each rag-retrieval fixture is embedded and run through the
production RAG path — ScopedContentRetrieverFactory (ANN filtered by the fixture's rag_scope metadata)
plus PermissionAwareMetadataFilter (drop docs whose required_permissions the caller lacks) — with
chunks collapsed to distinct document_ids, then scored against expected/forbidden doc_ids.

Caveat: this scores on the raw ANN order; the Java path applies a light ToolScorer re-rank on top,
so hit@5 here is a close proxy, not bit-identical. It is sufficient to confirm hit@5 > 0 (the #783
name-mismatch fix), the #779 permission-gating invariant, and RAG recall@k / forbidden-doc gating.

Deps: pg8000 (pure-Python PG driver: `pip install --user pg8000`). Everything else is stdlib.
Config: reads POS_MCP_DB_HOST/PORT/NAME/USER/PASSWORD and OLLAMA_EMBEDDING_BASE_URL from the
environment, falling back to ./.env (SPRING_DATASOURCE_USERNAME/PASSWORD, then POSTGRES_*).

Usage:
    pip install --user pg8000
    POS_MCP_DB_PASSWORD=... python3 scripts/eval_live.py          # or rely on .env
"""
import json
import os
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EVAL = ROOT / "pos-mcp-server/src/test/resources/eval"
K = 5
# Production RAG retrieval applies a cosine similarity floor (SessionAgentManager:
# ScopedContentRetrieverFactory.create(scope, 10, 0.6) and (scope, 20, 0.55)). Score at the loosest
# value a doc must clear to enter the pipeline (0.55) so recall@k isn't over-reported vs the live path.
RAG_MIN_SCORE = float(os.environ.get("EVAL_RAG_MIN_SCORE", "0.55"))
# #784: Reciprocal Rank Fusion constant for the dense+lexical hybrid diagnostic. Matches the
# application default (HybridRetrievalProperties.rrfK / mcp.rag.hybrid.rrf-k).
RRF_K = int(os.environ.get("EVAL_RRF_K", "60"))


def env_file(key):
    f = Path(os.environ.get("ENV_FILE", ROOT / ".env"))
    if not f.is_file():
        return None
    for line in f.read_text().splitlines():
        if line.startswith(key + "="):
            return line.split("=", 1)[1].strip().strip('"')
    return None


def cfg(key, *fallback_env_keys, default=None):
    if os.environ.get(key):
        return os.environ[key]
    for fk in fallback_env_keys:
        v = env_file(fk)
        if v:
            return v
    return default


DB_HOST = cfg("POS_MCP_DB_HOST", default="localhost")
DB_PORT = int(cfg("POS_MCP_DB_PORT", default="5432"))
DB_NAME = cfg("POS_MCP_DB_NAME", default="pos_mcp")
DB_USER = cfg("POS_MCP_DB_USER", "SPRING_DATASOURCE_USERNAME", "POSTGRES_USER")
DB_PASS = cfg("POS_MCP_DB_PASSWORD", "SPRING_DATASOURCE_PASSWORD", "POSTGRES_PASSWORD")
OLLAMA = cfg("OLLAMA_EMBEDDING_BASE_URL", default="http://localhost:11434").rstrip("/")
MODEL = cfg("OLLAMA_EMBEDDING_MODEL", default="nomic-embed-text")


def embed(text):
    """Embed via Ollama. Tries /api/embed (newer) then /api/embeddings (older)."""
    for path, payload, key in (
        ("/api/embed", {"model": MODEL, "input": text}, "embeddings"),
        ("/api/embeddings", {"model": MODEL, "prompt": text}, "embedding"),
    ):
        try:
            req = urllib.request.Request(
                OLLAMA + path,
                data=json.dumps(payload).encode(),
                headers={"Content-Type": "application/json"},
            )
            with urllib.request.urlopen(req, timeout=60) as resp:
                body = json.loads(resp.read())
            vec = body[key]
            return vec[0] if key == "embeddings" else vec
        except Exception:
            continue
    raise RuntimeError(f"embedding failed against {OLLAMA} (model {MODEL})")


def vec_literal(vec):
    return "[" + ",".join(repr(float(x)) for x in vec) + "]"


def suite(name):
    fixtures = []
    for f in sorted((EVAL / name).glob("*.json")):
        fixtures.extend(json.loads(f.read_text()).get("fixtures", []))
    return fixtures


def rrf_fuse(ranked_lists, k=RRF_K, limit=None):
    """#784: Reciprocal Rank Fusion of ordered doc-id lists — score(d) = Σ 1/(k + rank_i). Stable
    Python sort keeps first-seen order among equal scores, matching HybridContentRetriever."""
    scores, first_seen = {}, []
    for ranked in ranked_lists:
        for rank, doc in enumerate(ranked, start=1):
            if doc not in scores:
                first_seen.append(doc)
            scores[doc] = scores.get(doc, 0.0) + 1.0 / (k + rank)
    fused = sorted(first_seen, key=lambda d: scores[d], reverse=True)
    return fused[:limit] if limit else fused


def main():
    try:
        import pg8000.native
    except ImportError:
        sys.exit("Missing driver. Run:  pip install --user pg8000")
    if not DB_USER or not DB_PASS:
        sys.exit("Could not resolve DB user/password (env or .env: SPRING_DATASOURCE_USERNAME/PASSWORD).")

    con = pg8000.native.Connection(
        user=DB_USER, password=DB_PASS, host=DB_HOST, port=DB_PORT, database=DB_NAME
    )
    print(f"DB={DB_USER}@{DB_HOST}:{DB_PORT}/{DB_NAME}  embed={OLLAMA} ({MODEL})\n")

    def ann_facade(query_vec, perms, workflow, limit):
        rows = con.run(
            """
            SELECT t.name FROM mcp_tool t
            JOIN mcp_tool_workflow tw ON t.id = tw.tool_id
            JOIN mcp_workflow_state ws ON tw.workflow_state_id = ws.id
            WHERE t.enabled = true AND t.source <> 'openapi' AND t.embedding IS NOT NULL
              AND ws.name = :wf
              AND t.id IN (SELECT tool_id FROM mcp_tool_permission WHERE permission_code = ANY(:perms))
            ORDER BY t.embedding <=> CAST(:q AS vector), t.id
            LIMIT :k
            """,
            wf=workflow, perms=list(perms), q=vec_literal(query_vec), k=limit,
        )
        return [r[0] for r in rows]

    def rag_visible_docs(query_vec, scope, caller_perms, want):
        """Reproduce the production RAG path: ScopedContentRetrieverFactory (ANN filtered by the
        rag_scope metadata + a cosine similarity floor RAG_MIN_SCORE) + PermissionAwareMetadataFilter
        (drop docs whose required_permissions the caller lacks). Returns ordered distinct document_ids
        (chunks collapsed to their document)."""
        rows = con.run(
            """
            SELECT metadata->>'document_id'        AS document_id,
                   metadata->>'required_permissions' AS required_permissions
            FROM mcp_document_embedding
            WHERE metadata->>'rag_scope' = :scope AND embedding IS NOT NULL
              AND (1 - (embedding <=> CAST(:q AS vector))) >= :min_score
            ORDER BY embedding <=> CAST(:q AS vector), id
            LIMIT :limit
            """,
            scope=scope, q=vec_literal(query_vec), min_score=RAG_MIN_SCORE, limit=max(want * 10, 50),
        )
        caller = set(caller_perms) | {"AUTHENTICATED"}  # any authenticated caller holds AUTHENTICATED
        ordered = []
        for document_id, required in rows:
            if document_id is None:
                continue
            req = {p.strip() for p in (required or "").split(",") if p.strip()}
            # PermissionAwareMetadataFilter: public (no req) visible; else caller must hold >=1 code
            if req and not (req & caller):
                continue
            if document_id not in ordered:  # collapse chunks -> distinct docs, preserve rank order
                ordered.append(document_id)
        return ordered

    def rag_lexical_docs(query_text, scope, caller_perms, want):
        """#784: lexical (Postgres FTS) counterpart of rag_visible_docs — mirrors
        LexicalDocumentRetriever (websearch_to_tsquery / ts_rank_cd over content_tsv, scope-filtered)
        plus the same PermissionAwareMetadataFilter. Returns ordered distinct document_ids. Raises if
        the content_tsv column is absent (migration V28 not applied); the caller treats that as
        'lexical unavailable' and skips the diagnostic rather than failing."""
        rows = con.run(
            """
            SELECT metadata->>'document_id'          AS document_id,
                   metadata->>'required_permissions' AS required_permissions
            FROM mcp_document_embedding
            WHERE metadata->>'rag_scope' = :scope
              AND content_tsv @@ websearch_to_tsquery('english', :q)
            ORDER BY ts_rank_cd(content_tsv, websearch_to_tsquery('english', :q)) DESC, id
            LIMIT :limit
            """,
            scope=scope, q=query_text, limit=max(want * 10, 50),
        )
        caller = set(caller_perms) | {"AUTHENTICATED"}
        ordered = []
        for document_id, required in rows:
            if document_id is None:
                continue
            req = {p.strip() for p in (required or "").split(",") if p.strip()}
            if req and not (req & caller):
                continue
            if document_id not in ordered:
                ordered.append(document_id)
        return ordered

    # ---- #783: tool-selection hit@5 / MRR ---------------------------------
    hits, rr, violations, scored = [], [], [], 0
    for fx in suite("tool-selection"):
        actor = fx["actor"]
        perms = actor.get("permission_codes", [])
        wf = actor.get("workflow_state", "IDLE")
        expected = fx.get("expected", {})
        expected_tools = expected.get("tool_ids", [])
        forbidden = set(expected.get("forbidden_tool_ids", []))
        ranked = ann_facade(embed(fx["utterance"]), perms, wf, max(K, 10)) if perms else []
        selected_forbidden = [t for t in ranked[:K] if t in forbidden]
        if selected_forbidden:
            violations.append({
                "fixture_id": fx.get("fixture_id"),
                "utterance": fx.get("utterance"),
                "forbidden_selected": selected_forbidden,
                "permission_codes": perms,
                "workflow": wf,
                "top_k": ranked[:K],
            })
        if expected_tools:
            primary = expected_tools[0]
            top = ranked[:K]
            hits.append(1.0 if primary in top else 0.0)
            rr.append(1.0 / (top.index(primary) + 1) if primary in top else 0.0)
            scored += 1

    hit_at_5 = sum(hits) / len(hits) if hits else 0.0
    mrr = sum(rr) / len(rr) if rr else 0.0

    # ---- #783 AC3: RAG recall@k (scope + permission filtered) --------------
    recalls, rag_scored, rag_violations = [], 0, []
    for fx in suite("rag-retrieval"):
        actor = fx["actor"]
        perms = actor.get("permission_codes", [])
        scope = fx.get("rag_scope", "master")
        expected = fx.get("expected", {})
        expected_docs = expected.get("doc_ids", []) or []
        forbidden = set(expected.get("forbidden_doc_ids", []) or [])
        k = expected.get("k", K)
        visible = rag_visible_docs(embed(fx["query"]), scope, perms, k)
        top_k = visible[:k]
        forbidden_present = [d for d in top_k if d in forbidden]
        if forbidden_present:
            rag_violations.append({
                "fixture_id": fx.get("fixture_id"),
                "query": fx.get("query"),
                "forbidden_present": forbidden_present,
                "rag_scope": scope,
                "permission_codes": perms,
                "top_k": top_k,
            })
        if expected_docs:
            found = sum(1 for d in expected_docs if d in top_k)
            recalls.append(found / len(expected_docs))
            rag_scored += 1

    recall_at_k = sum(recalls) / len(recalls) if recalls else 0.0

    # ---- #784: lexical vs hybrid recall on the exact-code suite (diagnostic, NOT gated) ----
    # Scores the separate 'rag-lexical' suite under dense-only vs dense+lexical(RRF) so the value of
    # the hybrid path is measurable. Never contributes to threshold failures, and degrades to a
    # 'skipped' status (rather than aborting the eval) when the FTS column / fixtures are absent.
    rag_lexical_summary = {"status": "skipped: no rag-lexical fixtures"}
    lexical_fixtures = suite("rag-lexical")
    if lexical_fixtures:
        try:
            dense_recalls, hybrid_recalls, per_fixture = [], [], []
            for fx in lexical_fixtures:
                expected_docs = fx.get("expected", {}).get("doc_ids", []) or []
                if not expected_docs:
                    continue
                perms = fx.get("actor", {}).get("permission_codes", [])
                scope = fx.get("rag_scope", "master")
                k = fx.get("expected", {}).get("k", K)
                qvec = embed(fx["query"])
                dense = rag_visible_docs(qvec, scope, perms, k)[:k]
                lexical = rag_lexical_docs(fx["query"], scope, perms, k)
                hybrid = rrf_fuse([dense, lexical], k=RRF_K, limit=k)
                dense_r = sum(1 for d in expected_docs if d in dense) / len(expected_docs)
                hybrid_r = sum(1 for d in expected_docs if d in hybrid) / len(expected_docs)
                dense_recalls.append(dense_r)
                hybrid_recalls.append(hybrid_r)
                per_fixture.append({
                    "fixture_id": fx.get("fixture_id"), "query": fx.get("query"),
                    "expected": expected_docs, "dense_recall": round(dense_r, 4),
                    "hybrid_recall": round(hybrid_r, 4), "dense_top_k": dense, "hybrid_top_k": hybrid,
                })
            n = len(dense_recalls)
            rag_lexical_summary = {
                "status": "scored",
                "scored": n,
                "rrf_k": RRF_K,
                "dense_recall_at_k": round(sum(dense_recalls) / n, 4) if n else 0.0,
                "hybrid_recall_at_k": round(sum(hybrid_recalls) / n, 4) if n else 0.0,
                "detail": per_fixture,
            }
        except Exception as e:  # missing content_tsv (V28 not deployed), FTS error, etc.
            rag_lexical_summary = {"status": f"skipped: lexical FTS unavailable ({type(e).__name__}: {e})"}

    # ---- #779: permission gating (openapi tool) ---------------------------
    gating = {"status": "skipped"}
    row = con.run(
        """
        SELECT t.name, t.description, MIN(tp.permission_code) AS perm
        FROM mcp_tool t JOIN mcp_tool_permission tp ON tp.tool_id = t.id
        WHERE t.source = 'openapi' AND t.embedding IS NOT NULL
        GROUP BY t.id, t.name, t.description
        HAVING COUNT(*) = 1 AND MIN(tp.permission_code) <> 'AUTHENTICATED'
        LIMIT 1
        """
    )
    if row:
        name, desc, perm = row[0]
        qv = vec_literal(embed(desc))

        def openapi_hit(perms):
            r = con.run(
                """
                SELECT t.name FROM mcp_tool t
                WHERE t.source = 'openapi' AND t.embedding IS NOT NULL
                  AND t.id IN (SELECT tool_id FROM mcp_tool_permission WHERE permission_code = ANY(:perms))
                ORDER BY t.embedding <=> CAST(:q AS vector), t.id
                LIMIT :k
                """,
                perms=list(perms), q=qv, k=max(K, 10),
            )
            return name in [x[0] for x in r]

        without = openapi_hit(["AUTHENTICATED"])
        with_perm = openapi_hit(["AUTHENTICATED", perm])
        gating = {
            "status": "PASS" if (not without and with_perm) else "FAIL",
            "tool": name, "permission": perm,
            "present_without_permission": without, "present_with_permission": with_perm,
        }

    # AC4 (#783) thresholds — calibrated from the live baseline (hit@5 0.84 / MRR 0.77),
    # set with margin below observed. Override with EVAL_MIN_HIT5 / EVAL_MIN_MRR.
    # Floors calibrated with margin below the live alpha baseline (17-doc corpus):
    #   hit@5   0.84 -> 0.75  (~11%),  recall@k 0.9574 -> 0.85 (~11%),  MRR 0.77 -> 0.65 (~16%, wider —
    #   MRR is rank-sensitive so it gets more headroom). Recall confirmed at the production
    #   RAG_MIN_SCORE=0.55 floor on the preload-repopulated corpus (identical to the 0.0-threshold run:
    #   expected docs clear 0.55 comfortably). RAG forbidden leaks are always a hard fail.
    # Override with EVAL_MIN_HIT5 / EVAL_MIN_MRR / EVAL_MIN_RECALL.
    floor_hit5 = float(os.environ.get("EVAL_MIN_HIT5", "0.75"))
    floor_mrr = float(os.environ.get("EVAL_MIN_MRR", "0.65"))
    floor_recall = float(os.environ.get("EVAL_MIN_RECALL", "0.85"))
    failures = []
    if hit_at_5 < floor_hit5:
        failures.append(f"hit@5 {hit_at_5:.4f} < {floor_hit5}")
    if mrr < floor_mrr:
        failures.append(f"mrr {mrr:.4f} < {floor_mrr}")
    if violations:
        failures.append(f"forbidden_violations {len(violations)} > 0")
    if rag_violations:
        failures.append(f"rag_forbidden_violations {len(rag_violations)} > 0")
    if rag_scored and recall_at_k < floor_recall:
        failures.append(f"recall@k {recall_at_k:.4f} < {floor_recall}")
    if gating.get("status") == "FAIL":
        failures.append("permission_gating_779 FAIL")

    result = {
        "tool_selection": {"hit_at_5": round(hit_at_5, 4), "mrr": round(mrr, 4),
                            "scored": scored, "forbidden_violations": len(violations),
                            "forbidden_violation_detail": violations},
        "rag_retrieval": {"recall_at_k": round(recall_at_k, 4), "scored": rag_scored,
                          "forbidden_violations": len(rag_violations),
                          "forbidden_violation_detail": rag_violations},
        "rag_lexical_hybrid_784": rag_lexical_summary,
        "permission_gating_779": gating,
        "thresholds": {"min_hit_at_5": floor_hit5, "min_mrr": floor_mrr, "min_recall_at_k": floor_recall,
                       "passed": not failures, "failures": failures},
    }
    out = ROOT / "pos-mcp-server/target/eval/baseline-live-python.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))
    print(f"\nwrote {out}")
    con.close()
    if failures:
        print("\nTHRESHOLD FAIL: " + "; ".join(failures))
        sys.exit(1)
    print("\nTHRESHOLDS OK")


if __name__ == "__main__":
    main()

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
# #784: Reciprocal Rank Fusion constant for the dense+lexical hybrid diagnostic. Matches the
# application default (HybridRetrievalProperties.rrfK / mcp.rag.hybrid.rrf-k).
RRF_K = max(1, int(os.environ.get("EVAL_RRF_K", "60")))  # clamp: rank constant must be >= 1
# #1178: regression gate for the lexical path. Of the lexical fixtures where dense alone misses
# (dense_recall < 1.0), at least this fraction must be improved by hybrid RRF fusion. Applied only
# when the dense-miss denominator is non-zero, so a corpus where dense recalls everything cannot go
# red vacuously; a broken lexical path (tsquery construction, content_tsv loss on re-embed, RRF
# wiring) drives recovery to 0 and turns the suite red. Set to 0 to restore diagnostic-only mode.
MIN_LEXICAL_RECOVERY = float(os.environ.get("EVAL_MIN_LEXICAL_RECOVERY", "0.5"))
# #1164: tool-response coverage — cheap realistic-vs-no-tool classifier. A fixture counts as
# realistic-response when the gated ANN returns a candidate whose cosine similarity clears this
# floor; empty candidate set or a top hit below the floor is no-tool-available (the model has no
# plausibly-serving tool to pick). This is deliberately lighter than a grounded judge (#783/#1124).
TR_MIN_SIM = float(os.environ.get("EVAL_TOOL_RESPONSE_MIN_SIM", "0.5"))


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
# The embedding model must track the live pipeline (#1194: bge-m3 after the flip), so the .env
# value is honored when the shell env is unset. The base URL deliberately does NOT read .env — it
# holds the docker-internal hostname (http://ollama:11434 resolves only inside the compose network).
MODEL = cfg("OLLAMA_EMBEDDING_MODEL", "OLLAMA_EMBEDDING_MODEL", default="nomic-embed-text")
# #1194: which pgvector column the eval reads. Default preserves historical behavior (the 768
# `embedding` column). Set EVAL_EMBEDDING_COLUMN=embedding_1024 (together with
# OLLAMA_EMBEDDING_MODEL=bge-m3) to validate the 1024-dim bge-m3 path against the dual-column
# data BEFORE flipping the live config. Allowlisted because the name is interpolated into SQL.
EMB_COL = cfg("EVAL_EMBEDDING_COLUMN", "MCP_RAG_EMBEDDING_COLUMN", default="embedding")
if EMB_COL not in ("embedding", "embedding_1024"):
    sys.exit(f"Invalid EVAL_EMBEDDING_COLUMN '{EMB_COL}' (expected 'embedding' or 'embedding_1024')")
# Production RAG retrieval applies a cosine similarity floor (SessionAgentManager:
# ScopedContentRetrieverFactory.create(scope, 10, 0.6) and (scope, 20, 0.55)). Score at the loosest
# value a doc must clear to enter the pipeline — following the live MCP_RAG_TIER2_MIN_SCORE
# from .env when set (#1194: the floors flip with the embedding model) (0.55 by default) so recall@k isn't over-reported vs the live path.
RAG_MIN_SCORE = float(cfg("EVAL_RAG_MIN_SCORE", "MCP_RAG_TIER2_MIN_SCORE", default="0.55"))
# #1178: the rag-lexical block scores its DENSE pass at the primary production floor (0.6 —
# SessionAgentManager's semanticRetriever, ScopedContentRetrieverFactory.create(scope, 10, 0.6)),
# because that is the path whose live dense misses the dense-miss fixtures encode (#1170: VIN
# question, glossary.identifiers similarity 0.58 < 0.60). The gated rag-retrieval suite keeps the
# looser RAG_MIN_SCORE above, unchanged. Follows the live MCP_RAG_MIN_SCORE from .env when set.
LEX_DENSE_MIN_SCORE = float(cfg("EVAL_LEXICAL_DENSE_MIN_SCORE", "MCP_RAG_MIN_SCORE", default="0.6"))


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
    return fused[:limit] if limit is not None else fused


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
    print(f"DB={DB_USER}@{DB_HOST}:{DB_PORT}/{DB_NAME}  embed={OLLAMA} ({MODEL})  column={EMB_COL}\n")

    def ann_facade(query_vec, perms, workflow, limit):
        rows = con.run(
            f"""
            SELECT t.name FROM mcp_tool t
            JOIN mcp_tool_workflow tw ON t.id = tw.tool_id
            JOIN mcp_workflow_state ws ON tw.workflow_state_id = ws.id
            WHERE t.enabled = true AND t.source <> 'openapi' AND t.{EMB_COL} IS NOT NULL
              AND ws.name = :wf
              AND t.id IN (SELECT tool_id FROM mcp_tool_permission WHERE permission_code = ANY(:perms))
            ORDER BY t.{EMB_COL} <=> CAST(:q AS vector), t.id
            LIMIT :k
            """,
            wf=workflow, perms=list(perms), q=vec_literal(query_vec), k=limit,
        )
        return [r[0] for r in rows]

    def rag_visible_docs(query_vec, scope, caller_perms, want, min_score=RAG_MIN_SCORE, master_all_scopes=False):
        """Reproduce the production RAG path: ScopedContentRetrieverFactory (ANN filtered by the
        rag_scope metadata + a cosine similarity floor) + PermissionAwareMetadataFilter (drop docs
        whose required_permissions the caller lacks). Returns ordered distinct document_ids (chunks
        collapsed to their document).

        With master_all_scopes=True the 'master' scope drops the scope filter and searches every
        scope, mirroring #1169 (ScopedContentRetrieverFactory#create: master is the catch-all agent,
        not a literal doc scope). The gated rag-retrieval suite keeps the historical scoped behavior
        (default False) so its recall@k floor calibration is undisturbed; the #1178 lexical block
        opts in to reproduce the live dense path that produced the verified misses."""
        if master_all_scopes and scope == "master":
            rows = con.run(
                f"""
                SELECT metadata->>'document_id'        AS document_id,
                       metadata->>'required_permissions' AS required_permissions
                FROM mcp_document_embedding
                WHERE {EMB_COL} IS NOT NULL
                  AND (1 - ({EMB_COL} <=> CAST(:q AS vector))) >= :min_score
                ORDER BY {EMB_COL} <=> CAST(:q AS vector), id
                LIMIT :limit
                """,
                q=vec_literal(query_vec), min_score=min_score, limit=max(want * 10, 50),
            )
        else:
            rows = con.run(
                f"""
                SELECT metadata->>'document_id'        AS document_id,
                       metadata->>'required_permissions' AS required_permissions
                FROM mcp_document_embedding
                WHERE metadata->>'rag_scope' = :scope AND {EMB_COL} IS NOT NULL
                  AND (1 - ({EMB_COL} <=> CAST(:q AS vector))) >= :min_score
                ORDER BY {EMB_COL} <=> CAST(:q AS vector), id
                LIMIT :limit
                """,
                scope=scope, q=vec_literal(query_vec), min_score=min_score, limit=max(want * 10, 50),
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
        LexicalDocumentRetriever plus the same PermissionAwareMetadataFilter. Returns ordered
        distinct document_ids. Raises if the content_tsv column is absent (migration V28 not
        applied); the caller treats that as 'lexical unavailable' and skips the diagnostic rather
        than failing.

        Kept in lockstep with LexicalDocumentRetriever (#1178 — a drifted mirror is exactly the
        regression this suite exists to catch):
        - #1170: websearch_to_tsquery ANDs every term, so an NL question that merely CONTAINS an
          identifier matched nothing; the parsed tsquery is OR-transformed (its '&' operators swapped
          for '|' in text form — raw user text still passes only through websearch_to_tsquery, so no
          injection surface) and ts_rank_cd ranks the OR matches.
        - #1169: the 'master' scope is the catch-all agent, not a literal doc scope, so it searches
          all scopes (SQL_ALL_SCOPES); domain scopes keep their narrow filter."""
        or_tsquery = "replace(websearch_to_tsquery('english', :q)::text, ' & ', ' | ')::tsquery"
        scope_filter = "" if scope == "master" else "  AND metadata->>'rag_scope' = :scope\n"
        sql = (
            "SELECT metadata->>'document_id'          AS document_id,\n"
            "       metadata->>'required_permissions' AS required_permissions\n"
            "FROM mcp_document_embedding\n"
            f"WHERE content_tsv @@ {or_tsquery}\n"
            f"{scope_filter}"
            f"ORDER BY ts_rank_cd(content_tsv, {or_tsquery}) DESC, id\n"
            "LIMIT :limit"
        )
        params = {"q": query_text, "limit": max(want * 10, 50)}
        if scope != "master":
            params["scope"] = scope
        rows = con.run(sql, **params)
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

    # ---- #784/#1178: lexical vs hybrid recall on the exact-code suite ----
    # Scores the separate 'rag-lexical' suite under dense-only vs dense+lexical(RRF). The dense pass
    # runs at the primary production floor (LEX_DENSE_MIN_SCORE=0.6) with master searching all
    # scopes, reproducing the live path whose verified dense misses (#1170: VIN / invoice-number)
    # the dense-miss fixtures encode. #1178 adds a regression gate: fixtures where dense alone
    # misses form the dense_misses denominator, and hybrid must recover at least
    # MIN_LEXICAL_RECOVERY of them — a silently broken lexical path (tsquery construction,
    # content_tsv loss on re-embed, RRF wiring, flag off) drives recovery to 0 and turns the suite
    # red. Degrades to a 'skipped' status (never gated) when the FTS column / fixtures are absent.
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
                dense = rag_visible_docs(
                    qvec, scope, perms, k, min_score=LEX_DENSE_MIN_SCORE, master_all_scopes=True)[:k]
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
            dense_miss = [pf for pf in per_fixture if pf["dense_recall"] < 1.0]
            recovered = [pf for pf in dense_miss if pf["hybrid_recall"] > pf["dense_recall"]]
            rag_lexical_summary = {
                "status": "scored",
                "scored": n,
                "rrf_k": RRF_K,
                "dense_min_score": LEX_DENSE_MIN_SCORE,
                "dense_recall_at_k": round(sum(dense_recalls) / n, 4) if n else 0.0,
                "hybrid_recall_at_k": round(sum(hybrid_recalls) / n, 4) if n else 0.0,
                "dense_misses": len(dense_miss),
                "hybrid_recovered": len(recovered),
                "recovery_rate": round(len(recovered) / len(dense_miss), 4) if dense_miss else None,
                "dense_miss_fixture_ids": [pf["fixture_id"] for pf in dense_miss],
                "detail": per_fixture,
            }
        except Exception as e:  # missing content_tsv (V28 not deployed), FTS error, etc.
            rag_lexical_summary = {"status": f"skipped: lexical FTS unavailable ({type(e).__name__}: {e})"}

    # ---- #1164: tool-response coverage (realistic vs no-tool-available) ----
    # Role-authored questions written FROM the personas, not from the tool set, run through the
    # same gated selection path. Diagnostic only — never contributes to threshold failures. The
    # gap_candidates output (actual no-tool-available, grouped by the author's gap_hypothesis) is
    # the triage worklist: new-tool candidates feed the tool roadmap, description-gap candidates
    # feed a *FacadeTool @Tool description-tightening pass.
    tool_response_summary = {"status": "skipped: no tool-response fixtures"}
    tr_fixtures = suite("tool-response")
    if tr_fixtures:

        def ann_facade_scored(query_vec, perms, workflow, limit):
            """ann_facade + cosine similarity of each candidate (selection path, scored).

            ORDER BY repeats the <=> expression rather than sorting by the sim alias: the point of
            this query is to mirror the production ANN SQL (ToolMetadataRepositoryImpl), and
            `ORDER BY embedding <=> constant` is the exact operator-ordering pattern pgvector
            indexes match — same convention as every other ANN query in this script."""
            rows = con.run(
                f"""
                SELECT t.name, 1 - (t.{EMB_COL} <=> CAST(:q AS vector)) AS sim
                FROM mcp_tool t
                JOIN mcp_tool_workflow tw ON t.id = tw.tool_id
                JOIN mcp_workflow_state ws ON tw.workflow_state_id = ws.id
                WHERE t.enabled = true AND t.source <> 'openapi' AND t.{EMB_COL} IS NOT NULL
                  AND ws.name = :wf
                  AND t.id IN (SELECT tool_id FROM mcp_tool_permission WHERE permission_code = ANY(:perms))
                ORDER BY t.{EMB_COL} <=> CAST(:q AS vector), t.id
                LIMIT :k
                """,
                wf=workflow, perms=list(perms), q=vec_literal(query_vec), k=limit,
            )
            return [(r[0], float(r[1])) for r in rows]

        tr_counts = {"realistic-response": 0, "no-tool-available": 0}
        tr_confusion, tr_mismatches, tr_gap_candidates = {}, [], {}
        for fx in tr_fixtures:
            actor = fx["actor"]
            # Every authenticated caller holds the AUTHENTICATED pseudo-permission (same convention
            # as the RAG path above), so AUTHENTICATED-gated facades are visible to all fixtures.
            perms = sorted(set(actor.get("permission_codes", [])) | {"AUTHENTICATED"})
            wf = actor.get("workflow_state", "IDLE")
            ranked = ann_facade_scored(embed(fx["utterance"]), perms, wf, K)
            top_name, top_sim = (ranked[0] if ranked else (None, None))
            actual = (
                "realistic-response"
                if top_sim is not None and top_sim >= TR_MIN_SIM
                else "no-tool-available"
            )
            expected_outcome = fx.get("expected", {}).get("outcome", "no-tool-available")
            tr_counts[actual] += 1
            cell = f"expected={expected_outcome}|actual={actual}"
            tr_confusion[cell] = tr_confusion.get(cell, 0) + 1
            if actual != expected_outcome:
                tr_mismatches.append({
                    "fixture_id": fx.get("fixture_id"),
                    "utterance": fx.get("utterance"),
                    "expected": expected_outcome,
                    "actual": actual,
                    "top_candidate": top_name,
                    "top_similarity": round(top_sim, 4) if top_sim is not None else None,
                })
            if actual == "no-tool-available":
                hypothesis = fx.get("gap_hypothesis", "n/a")
                tr_gap_candidates.setdefault(hypothesis, []).append({
                    "fixture_id": fx.get("fixture_id"),
                    "utterance": fx.get("utterance"),
                    "expected": expected_outcome,
                    "top_candidate": top_name,
                    "top_similarity": round(top_sim, 4) if top_sim is not None else None,
                })
        tr_total = len(tr_fixtures)
        tool_response_summary = {
            "status": "scored",
            "scored": tr_total,
            "min_similarity": TR_MIN_SIM,
            "realistic_rate": round(tr_counts["realistic-response"] / tr_total, 4),
            "no_tool_rate": round(tr_counts["no-tool-available"] / tr_total, 4),
            "outcome_confusion": tr_confusion,
            "outcome_mismatch_detail": tr_mismatches,
            "gap_candidates": tr_gap_candidates,
        }

    # ---- #779: permission gating (openapi tool) ---------------------------
    gating = {"status": "skipped"}
    row = con.run(
        f"""
        SELECT t.name, t.description, MIN(tp.permission_code) AS perm
        FROM mcp_tool t JOIN mcp_tool_permission tp ON tp.tool_id = t.id
        WHERE t.source = 'openapi' AND t.{EMB_COL} IS NOT NULL
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
                f"""
                SELECT t.name FROM mcp_tool t
                WHERE t.source = 'openapi' AND t.{EMB_COL} IS NOT NULL
                  AND t.id IN (SELECT tool_id FROM mcp_tool_permission WHERE permission_code = ANY(:perms))
                ORDER BY t.{EMB_COL} <=> CAST(:q AS vector), t.id
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

    # AC4 (#783) thresholds — re-baselined 2026-08-09 (#1179) on the bge-m3 1024-dim pipeline
    # (#1194 cutover). Method (unchanged from #1124): floors ~11% below the live-observed alpha
    # baseline — min(mean - 2*stddev, mean * 0.89) as printed by `--baseline` — so the nightly gate
    # tolerates a corpus-scale re-embedding swing without going red on a healthy corpus while still
    # catching a real regression.
    #
    # Measured basis (2026-08-09, `eval_live.py --baseline 3` on alpha):
    #   image sha-486c132; pipeline: bge-m3 1024-dim (MCP_RAG_EMBEDDING_COLUMN=embedding_1024,
    #   MCP_RAG_DIMENSION=1024, OLLAMA_EMBEDDING_MODEL=bge-m3), similarity floors
    #   MCP_RAG_MIN_SCORE=0.45 / MCP_RAG_TIER2_MIN_SCORE=0.40 (bge-m3 calibration — see
    #   gate5-rag-hybrid-design.md G5.4), hybrid dense+lexical ON, OR-semantics tsquery,
    #   compound-slots rerank ON. Eval scored at the matching floors (EVAL_RAG_MIN_SCORE=0.40,
    #   EVAL_LEXICAL_DENSE_MIN_SCORE=0.45).
    #   Per-run values — identical across all 3 runs (stddev 0.0; per-run embedding is
    #   deterministic, as first observed in the #1124 re-baseline):
    #     hit@5 0.76 / 0.76 / 0.76        -> floor 0.68  (mean*0.89)
    #     MRR   0.7333 / 0.7333 / 0.7333  -> floor 0.65  (mean*0.89)
    #     recall@k 0.9216 / 0.9216 / 0.9216 -> floor 0.82 (mean*0.89)
    #   For reference, the 768/nomic pipeline measured the same day: hit@5 0.76, MRR 0.7222,
    #   recall@k 0.8431 — the 1024 cutover raised gated recall by ~0.08.
    # RAG forbidden leaks are always a hard fail. Re-run this calibration whenever the corpus or
    # the embedding pipeline changes materially. Override with EVAL_MIN_HIT5/EVAL_MIN_MRR/
    # EVAL_MIN_RECALL.
    #
    # Hybrid-gating decision (#1179, standing question): the gated recall@k floor deliberately
    # scores the DENSE path only. Rationale: (1) a dense/embedding regression must not be masked
    # by a lexical-FTS rescue — hybrid-scored recall would stay green while the embedding pipeline
    # rotted; (2) the lexical/hybrid path has its own regression gate (#1178 lexical_recovery
    # below: >=50% of dense-miss fixtures must be recovered by hybrid RRF), so a lexical break
    # trips the suite independently. Together the two gates cover both halves of the hybrid
    # retriever without either masking the other.
    floor_hit5 = float(os.environ.get("EVAL_MIN_HIT5", "0.68"))
    floor_mrr = float(os.environ.get("EVAL_MIN_MRR", "0.65"))
    floor_recall = float(os.environ.get("EVAL_MIN_RECALL", "0.82"))
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
    # #1178: lexical regression gate — only when the dense-miss denominator is non-zero (see
    # MIN_LEXICAL_RECOVERY above). A skipped lexical block (no fixtures / FTS unavailable) never gates.
    if rag_lexical_summary.get("status") == "scored" and rag_lexical_summary.get("dense_misses", 0) > 0:
        recovery = rag_lexical_summary.get("recovery_rate") or 0.0
        if recovery < MIN_LEXICAL_RECOVERY:
            failures.append(
                f"lexical_recovery {recovery:.4f} < {MIN_LEXICAL_RECOVERY} "
                f"(dense_misses={rag_lexical_summary['dense_misses']}, "
                f"hybrid_recovered={rag_lexical_summary['hybrid_recovered']})")

    result = {
        "tool_selection": {"hit_at_5": round(hit_at_5, 4), "mrr": round(mrr, 4),
                            "scored": scored, "forbidden_violations": len(violations),
                            "forbidden_violation_detail": violations},
        "rag_retrieval": {"recall_at_k": round(recall_at_k, 4), "scored": rag_scored,
                          "forbidden_violations": len(rag_violations),
                          "forbidden_violation_detail": rag_violations},
        "rag_lexical_hybrid_784": rag_lexical_summary,
        "tool_response_coverage": tool_response_summary,
        "permission_gating_779": gating,
        "thresholds": {"min_hit_at_5": floor_hit5, "min_mrr": floor_mrr, "min_recall_at_k": floor_recall,
                       "min_lexical_recovery": MIN_LEXICAL_RECOVERY,
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


def baseline_mode(runs: int) -> None:
    """#1179: measure the stable metric level + wobble on the CURRENT pipeline and suggest floors.

    Re-runs the full eval `runs` times as subprocesses with the metric floors disabled (forbidden
    leaks still hard-fail and abort the baseline), collects hit@5 / MRR / recall@k from each run's
    baseline-live-python.json, and prints mean/min/max/stddev plus a suggested floor per metric:
    the lower of (mean - 2*stddev) and (mean * 0.89) — the variance rule from #1179 combined with
    the ~11% headroom method used for the deterministic 2026-07-29 calibration (stddev may be 0
    because per-run embedding is deterministic; the floor must still absorb a corpus-scale
    re-embed swing). Run this on alpha after any corpus or retrieval-pipeline change, then commit
    the new EVAL_MIN_* defaults with this output as the measured basis.
    """
    import statistics
    import subprocess

    out_json = ROOT / "pos-mcp-server/target/eval/baseline-live-python.json"
    env = dict(os.environ, EVAL_MIN_HIT5="0", EVAL_MIN_MRR="0", EVAL_MIN_RECALL="0",
               EVAL_MIN_LEXICAL_RECOVERY="0")
    samples: dict[str, list[float]] = {"hit_at_5": [], "mrr": [], "recall_at_k": []}
    for i in range(1, runs + 1):
        print(f"=== baseline run {i}/{runs} ===")
        proc = subprocess.run([sys.executable, __file__], env=env)
        if proc.returncode != 0:
            sys.exit(f"baseline run {i} failed hard (forbidden leak or infra error) — aborting.")
        result = json.loads(out_json.read_text())
        samples["hit_at_5"].append(result["tool_selection"]["hit_at_5"])
        samples["mrr"].append(result["tool_selection"]["mrr"])
        samples["recall_at_k"].append(result["rag_retrieval"]["recall_at_k"])

    stats = {}
    print(f"\n#1179 baseline over {runs} runs (current pipeline):")
    print(f"{'metric':<12} {'mean':>8} {'min':>8} {'max':>8} {'stddev':>8} {'suggested_floor':>16}")
    for metric, values in samples.items():
        mean = statistics.fmean(values)
        stddev = statistics.pstdev(values)
        floor = round(max(0.0, min(mean - 2 * stddev, mean * 0.89)), 2)
        stats[metric] = {"runs": values, "mean": round(mean, 4), "min": min(values),
                         "max": max(values), "stddev": round(stddev, 4), "suggested_floor": floor}
        print(f"{metric:<12} {mean:>8.4f} {min(values):>8.4f} {max(values):>8.4f} "
              f"{stddev:>8.4f} {floor:>16.2f}")
    stats_path = out_json.parent / "baseline-stats.json"
    stats_path.write_text(json.dumps({"runs": runs, "metrics": stats}, indent=2))
    print(f"\nwrote {stats_path}")
    print("Commit new EVAL_MIN_* defaults in this file with the table above as the measured basis (#1179).")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--baseline":
        baseline_mode(int(sys.argv[2]) if len(sys.argv) > 2 else 3)
    else:
        main()

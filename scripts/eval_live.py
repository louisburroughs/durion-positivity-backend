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

Caveat: this scores on the raw ANN order; the Java path applies a light ToolScorer re-rank on top,
so hit@5 here is a close proxy, not bit-identical. It is sufficient to confirm hit@5 > 0 (the #783
name-mismatch fix) and the #779 permission-gating invariant.

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
    floor_hit5 = float(os.environ.get("EVAL_MIN_HIT5", "0.75"))
    floor_mrr = float(os.environ.get("EVAL_MIN_MRR", "0.65"))
    failures = []
    if hit_at_5 < floor_hit5:
        failures.append(f"hit@5 {hit_at_5:.4f} < {floor_hit5}")
    if mrr < floor_mrr:
        failures.append(f"mrr {mrr:.4f} < {floor_mrr}")
    if violations:
        failures.append(f"forbidden_violations {len(violations)} > 0")
    if gating.get("status") == "FAIL":
        failures.append("permission_gating_779 FAIL")

    result = {
        "tool_selection": {"hit_at_5": round(hit_at_5, 4), "mrr": round(mrr, 4),
                            "scored": scored, "forbidden_violations": len(violations),
                            "forbidden_violation_detail": violations},
        "permission_gating_779": gating,
        "thresholds": {"min_hit_at_5": floor_hit5, "min_mrr": floor_mrr,
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

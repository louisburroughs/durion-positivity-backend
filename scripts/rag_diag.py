#!/usr/bin/env python3
"""Diagnose why RAG recall@k is ~0 on the live corpus. Reuses eval_live.py's DB/embed config
(.env resolution, Ollama embedding). Read-only. Prints:
  1. row count + non-null embedding count in mcp_document_embedding
  2. distinct rag_scope -> chunk count
  3. distinct (document_id, rag_scope) -> chunk count
  4. one raw metadata sample (to confirm the key names)
  5. per-fixture: scope, expected doc_ids, and the top-5 distinct docs actually retrieved

Usage:  OLLAMA_EMBEDDING_BASE_URL=http://localhost:11434 python3 scripts/rag_diag.py
"""
import json
import sys

import eval_live as E  # reuse DB_* config, .env resolution, embed(), vec_literal()


def main():
    try:
        import pg8000.native
    except ImportError:
        sys.exit("Missing driver. Run:  pip install --user pg8000")
    con = pg8000.native.Connection(
        user=E.DB_USER, password=E.DB_PASS, host=E.DB_HOST, port=E.DB_PORT, database=E.DB_NAME
    )
    print(f"DB={E.DB_USER}@{E.DB_HOST}:{E.DB_PORT}/{E.DB_NAME}  embed={E.OLLAMA} ({E.MODEL})\n")

    total = con.run("SELECT COUNT(*), COUNT(embedding) FROM mcp_document_embedding")[0]
    print(f"[1] mcp_document_embedding rows={total[0]} with_embedding={total[1]}\n")

    print("[2] distinct rag_scope -> chunk count")
    for r in con.run(
        "SELECT metadata->>'rag_scope' AS scope, COUNT(*) "
        "FROM mcp_document_embedding GROUP BY 1 ORDER BY 2 DESC"
    ):
        print(f"    {r[0]!r:20} {r[1]}")

    print("\n[3] distinct document_id (scope) -> chunk count")
    for r in con.run(
        "SELECT metadata->>'document_id' AS doc, metadata->>'rag_scope' AS scope, COUNT(*) "
        "FROM mcp_document_embedding GROUP BY 1,2 ORDER BY 1"
    ):
        print(f"    {r[0]!r:34} scope={r[1]!r:14} chunks={r[2]}")

    print("\n[4] raw metadata sample (confirms key names)")
    sample = con.run("SELECT metadata FROM mcp_document_embedding LIMIT 1")
    if sample:
        print("    " + json.dumps(sample[0][0], indent=2)[:800])

    print("\n[5] per-fixture retrieval (first 6 positive fixtures)")
    shown = 0
    for fx in E.suite("rag-retrieval"):
        exp = fx.get("expected", {})
        expected = exp.get("doc_ids", []) or []
        if not expected:
            continue
        scope = fx.get("rag_scope", "master")
        rows = con.run(
            "SELECT metadata->>'document_id' FROM mcp_document_embedding "
            "WHERE metadata->>'rag_scope' = :scope AND embedding IS NOT NULL "
            "ORDER BY embedding <=> CAST(:q AS vector), id LIMIT 50",
            scope=scope, q=E.vec_literal(E.embed(fx["query"])),
        )
        seen, top = set(), []
        for (doc,) in rows:
            if doc and doc not in seen:
                seen.add(doc)
                top.append(doc)
        hit = any(d in top[:5] for d in expected)
        print(f"    {fx['fixture_id']}")
        print(f"       scope={scope!r} expected={expected} hit={hit}")
        print(f"       top5={top[:5]}  (scope returned {len(rows)} chunks, {len(top)} distinct docs)")
        shown += 1
        if shown >= 6:
            break
    con.close()


if __name__ == "__main__":
    main()

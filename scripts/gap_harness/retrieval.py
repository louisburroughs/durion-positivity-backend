"""Retrieved-context capture — reproduce the production RAG retrieval path against Postgres.

Capturing the retrieved context is mandatory (§3.2): it is what lets the classifier tell a missing
doc from a doc that exists but was not retrieved. The MCP chat API returns only the answer text, so
this module reproduces the retrieval that fed the prompt — exactly the approach ``eval_live.py``
already takes, sharing its embedding + SQL so the captured context matches the live path.

  * dense   — ScopedContentRetrieverFactory (ANN filtered by ``rag_scope`` + cosine floor) +
              PermissionAwareMetadataFilter (drop docs the caller lacks perms for).
  * lexical — LexicalDocumentRetriever (Postgres FTS over ``content_tsv``, scope-filtered) + the
              same permission filter. Absent ``content_tsv`` (migration not applied) -> lexical
              unavailable, and the flip-threshold analysis degrades to "skipped".

This is the only module that needs a live DB + embedding model; everything downstream runs on its
output and can be replayed offline from a raw dump.
"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Optional

from .model import RetrievedDoc

_SCRIPTS = Path(__file__).resolve().parents[1]
if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

AUTHENTICATED = "AUTHENTICATED"


def _perm_visible(required: Optional[str], caller: set[str]) -> bool:
    req = {p.strip() for p in (required or "").split(",") if p.strip()}
    req -= {AUTHENTICATED}
    if not req:
        return True
    return bool(req & (caller | {AUTHENTICATED}))


class DbRetriever:
    """Live retrieval reproduction. Constructed with a pg8000 connection and the eval_live helpers
    (embed, vec_literal, RAG_MIN_SCORE, RRF_K) so config + embedding stay identical to the gate."""

    def __init__(self, connection, embed, vec_literal, min_score: float):
        self._con = connection
        self._embed = embed
        self._vec = vec_literal
        self._min_score = min_score

    @classmethod
    def connect(cls):
        """Open a connection using eval_live's config + .env resolution. Requires pg8000 + a live DB
        + a reachable embedding model, exactly like ``scripts/run-live-eval.sh``."""
        import eval_live as E  # noqa: N812  (shared config/helpers; safe to import offline)

        try:
            import pg8000.native
        except ImportError as e:  # pragma: no cover - environment dependent
            raise RuntimeError("Missing driver. Run: pip install --user pg8000") from e
        if not E.DB_USER or not E.DB_PASS:
            raise RuntimeError("Could not resolve DB user/password (env or .env).")
        con = pg8000.native.Connection(
            user=E.DB_USER, password=E.DB_PASS, host=E.DB_HOST, port=E.DB_PORT, database=E.DB_NAME
        )
        return cls(con, E.embed, E.vec_literal, E.RAG_MIN_SCORE)

    def dense_docs(self, query: str, scope: str, perms: list[str], k: int) -> list[RetrievedDoc]:
        qvec = self._vec(self._embed(query))
        rows = self._con.run(
            """
            SELECT metadata->>'document_id'          AS document_id,
                   metadata->>'required_permissions' AS required_permissions,
                   1 - (embedding <=> CAST(:q AS vector)) AS score
            FROM mcp_document_embedding
            WHERE metadata->>'rag_scope' = :scope AND embedding IS NOT NULL
              AND (1 - (embedding <=> CAST(:q AS vector))) >= :min_score
            ORDER BY embedding <=> CAST(:q AS vector), id
            LIMIT :limit
            """,
            scope=scope, q=qvec, min_score=self._min_score, limit=max(k * 10, 50),
        )
        return self._collapse(rows, set(perms), k)

    def lexical_docs(self, query: str, scope: str, perms: list[str], k: int) -> list[RetrievedDoc]:
        rows = self._con.run(
            """
            SELECT metadata->>'document_id'          AS document_id,
                   metadata->>'required_permissions' AS required_permissions,
                   ts_rank_cd(content_tsv, websearch_to_tsquery('english', :q)) AS score
            FROM mcp_document_embedding
            WHERE metadata->>'rag_scope' = :scope
              AND content_tsv @@ websearch_to_tsquery('english', :q)
            ORDER BY ts_rank_cd(content_tsv, websearch_to_tsquery('english', :q)) DESC, id
            LIMIT :limit
            """,
            scope=scope, q=query, limit=max(k * 10, 50),
        )
        return self._collapse(rows, set(perms), k)

    @staticmethod
    def _collapse(rows, caller: set[str], k: int) -> list[RetrievedDoc]:
        """Apply the permission filter and collapse chunks to distinct documents, preserving rank
        order and the best score seen per doc."""
        out: list[RetrievedDoc] = []
        seen: set[str] = set()
        for document_id, required, score in rows:
            if document_id is None or not _perm_visible(required, caller):
                continue
            if document_id in seen:
                continue
            seen.add(document_id)
            out.append(RetrievedDoc(doc_id=document_id, score=float(score) if score is not None else 0.0))
            if len(out) >= max(k * 10, 50):
                break
        return out

    def close(self):
        try:
            self._con.close()
        except Exception:  # pragma: no cover
            pass

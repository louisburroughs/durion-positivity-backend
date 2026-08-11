"""Question sourcing (§7).

Three sources, blended and de-duplicated:
  * ``fixture``   — reuse the existing ``rag-retrieval`` / ``rag-lexical`` eval fixtures (they carry
    query + actor + expected doc ids), so the harness inherits the curated positive/gated set.
  * ``synthetic`` — the gap-harness question set (this package's JSON), SME-authored with
    ``expected_facts`` for grounded judging and breadth coverage (per-scope, exact-code, negative).
  * ``usage``     — real queries seeded from ``mcp_tool_invocation_log`` / NLTI telemetry: the best
    gap signal because it is what people actually ask. These arrive without ground truth, so they
    exercise refusal detection + gap discovery (content lookup), not the grounded judge.

Seed from real usage first, blend synthetic for breadth.
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Iterable

from .model import Actor, Question


def _norm(query: str) -> str:
    return re.sub(r"\s+", " ", (query or "").strip().lower())


def load_question_files(paths: Iterable[Path]) -> list[Question]:
    """Load gap-harness question JSON files ({"questions": [ {Question}, ... ]})."""
    out: list[Question] = []
    for p in paths:
        data = json.loads(Path(p).read_text())
        for q in data.get("questions", []):
            out.append(Question.from_dict(q))
    return out


def from_rag_fixtures(fixture_dirs: Iterable[Path]) -> list[Question]:
    """Convert existing rag-retrieval / rag-lexical fixtures into Questions. Only positive fixtures
    (those declaring expected ``doc_ids``) become questions; pure forbidden-doc negatives are left
    to ``eval_live.py``. Ground truth comes later from the expected docs' verified facts."""
    out: list[Question] = []
    for d in fixture_dirs:
        for f in sorted(Path(d).glob("*.json")):
            for fx in json.loads(f.read_text()).get("fixtures", []):
                expected = fx.get("expected", {}) or {}
                doc_ids = expected.get("doc_ids", []) or []
                if not doc_ids:
                    continue
                out.append(
                    Question(
                        id=fx.get("fixture_id", f"fixture-{len(out)}"),
                        query=fx["query"],
                        actor=Actor.from_dict(fx.get("actor", {})),
                        rag_scope=fx.get("rag_scope", "master"),
                        expected_doc_ids=tuple(doc_ids),
                        tags=tuple(fx.get("tags", []) or []),
                        source="fixture",
                        k=int(expected.get("k", 5)),
                    )
                )
    return out


def from_usage_rows(rows: Iterable[dict]) -> list[Question]:
    """Convert real-usage rows into Questions. Each row: {utterance, role?, permission_codes?,
    rag_scope?}. Real usage rarely carries ground truth; these seed refusal + gap discovery."""
    out: list[Question] = []
    for i, row in enumerate(rows):
        utterance = row.get("utterance") or row.get("query")
        if not utterance:
            continue
        out.append(
            Question(
                id=row.get("id", f"usage-{i}"),
                query=utterance,
                actor=Actor(
                    role=row.get("role", "ROLE_USER"),
                    permission_codes=tuple(row.get("permission_codes", []) or []),
                ),
                rag_scope=row.get("rag_scope", "master"),
                source="usage",
            )
        )
    return out


def dedupe(questions: Iterable[Question]) -> list[Question]:
    """Drop duplicate queries, keeping the first (source precedence is the caller's ordering).
    Questions with ground truth should be ordered ahead of bare usage rows so they win."""
    seen: set[str] = set()
    out: list[Question] = []
    for q in questions:
        key = _norm(q.query)
        if key in seen:
            continue
        seen.add(key)
        out.append(q)
    return out

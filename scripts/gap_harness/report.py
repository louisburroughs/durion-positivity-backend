"""Human-facing + machine-readable outputs (§6, §9).

Four outputs:
  * gap report — proposed docs for corpus gaps (#1). Propose, never author: each entry is a
    skeleton for a human to write and source-verify, never an ingested doc.
  * results — the full per-question machine-readable record (raw dump / replay input).
  * judge-accuracy report — so results are trusted proportionally to grader quality (§4).
  * flip-threshold evidence — dense-vs-hybrid recovery aggregate (§8).

The gap report deliberately carries a loud guardrail banner: a model authoring a doc to answer its
own question injects plausible-but-wrong ground truth that survives casual review and poisons
retrieval. Auto-drafting a skeleton is fine; auto-ingesting is not.
"""

from __future__ import annotations

from collections import defaultdict
from typing import Iterable, Optional

from .fusion import FlipDecision
from .judge import JudgeAccuracy
from .model import (
    CAUSE_CORPUS_GAP,
    CAUSE_GENERATION,
    CAUSE_PERMISSION_GATING,
    CAUSE_RETRIEVAL_MISS,
    VERDICT_CORRECT,
    VERDICT_MISLEADING,
    VERDICT_REFUSED,
    Result,
)

GUARDRAIL_BANNER = (
    "PROPOSED GAPS — DO NOT INGEST AS-IS. A human authors each doc and source-verifies it "
    "(the `_Verified: …_` convention). A model authoring docs to answer its own questions injects "
    "plausible-but-wrong ground truth. Auto-drafting this skeleton is fine; auto-ingesting is not."
)

# rag_scope -> the module(s) whose source is the likely authoritative reference for a new doc.
_SCOPE_TO_SOURCE = {
    "order": ["pos-order/…"],
    "pricing": ["pos-price/…"],
    "tax": ["pos-tax/…", "pos-tax-common/…"],
    "customer": ["pos-customer/…"],
    "inventory": ["pos-inventory/…"],
    "accounting": ["pos-accounting/…"],
    "reporting": ["pos-accounting/…", "pos-workorder/…"],
    "admin": ["pos-security-service/…", "pos-workorder/…"],
    "security": ["pos-security-service/…"],
    "events": ["pos-event-receiver/…", "pos-mcp-server/…"],
    "hr": ["pos-people/…"],
    "shopmanager": ["pos-workorder/…"],
    "master": ["(cross-cutting — pick the owning module)"],
}


def _candidate_sources(scope: str) -> list[str]:
    return _SCOPE_TO_SOURCE.get(scope, ["(identify the owning pos-* module)"])


def build_gap_report(results: Iterable[Result]) -> dict:
    """Group corpus-gap failures by topic into proposed-doc entries (§6)."""
    by_topic: dict[str, list[Result]] = defaultdict(list)
    for r in results:
        if r.classification.cause == CAUSE_CORPUS_GAP:
            key = r.question.topic or r.question.query
            by_topic[key].append(r)

    entries = []
    for topic, rs in sorted(by_topic.items()):
        scope = rs[0].question.rag_scope
        failing_queries = [r.question.query for r in rs]
        expected_docs = sorted({d for r in rs for d in r.question.expected_doc_ids})
        entries.append(
            {
                "topic": topic,
                "suggested_scope": scope,
                "failing_queries": failing_queries,
                "expected_doc_ids": expected_docs,
                "candidate_sources": _candidate_sources(scope),
                "draft_outline": _draft_outline(topic, scope, expected_docs, failing_queries),
                "occurrences": len(rs),
            }
        )
    entries.sort(key=lambda e: (-e["occurrences"], e["topic"]))
    return {"guardrail": GUARDRAIL_BANNER, "gap_count": len(entries), "entries": entries}


def _draft_outline(topic: str, scope: str, expected_docs: list[str], queries: list[str]) -> list[str]:
    """Auto-draft a *skeleton* (allowed by §6) — a header stub in the corpus's conventional shape
    plus section prompts from the failing queries. A human fills and source-verifies it."""
    doc_id = expected_docs[0] if expected_docs else f"{scope}.<topic-slug>"
    outline = [
        f"# {topic.title()}",
        f"RAG id: `{doc_id}`",
        f"RAG scope: `{scope}`",
        "Required permissions: `<fill: least-privilege code(s), or AUTHENTICATED>`",
        "## Purpose",
        "<one paragraph: what this doc answers and who asks it>",
    ]
    for q in queries[:8]:
        outline.append(f"## {q}")
        outline.append("<answer, grounded in module source>")
    outline.append("_Verified: `<module>` `<Class/field/method>` (source-verify before ingest)_")
    return outline


def summarize(results: list[Result]) -> dict:
    """Top-line counts: verdict distribution + taxonomy distribution."""
    verdicts = {VERDICT_CORRECT: 0, VERDICT_REFUSED: 0, VERDICT_MISLEADING: 0}
    causes = {
        CAUSE_CORPUS_GAP: 0,
        CAUSE_RETRIEVAL_MISS: 0,
        CAUSE_GENERATION: 0,
        CAUSE_PERMISSION_GATING: 0,
    }
    ungraded = 0
    for r in results:
        verdicts[r.grade.verdict] = verdicts.get(r.grade.verdict, 0) + 1
        if r.classification.cause in causes:
            causes[r.classification.cause] += 1
        if r.grade.judge_error and r.grade.judge_error.startswith("ungraded"):
            ungraded += 1
    n = len(results)
    return {
        "questions": n,
        "verdicts": verdicts,
        "taxonomy": causes,
        "ungraded": ungraded,
        "honesty_note": (
            "misleading detection is partial (§4): a 0 in `misleading` does not mean all answers are "
            "correct — trust the count proportionally to the judge-accuracy report."
        ),
    }


def render_gap_report_md(report: dict) -> str:
    lines = ["# RAG corpus gap report", "", f"> {report['guardrail']}", "", f"**{report['gap_count']} proposed doc(s).**", ""]
    if not report["entries"]:
        lines.append("_No corpus gaps discovered in this run._")
        return "\n".join(lines) + "\n"
    for e in report["entries"]:
        lines.append(f"## {e['topic']}  ·  scope `{e['suggested_scope']}`  ·  {e['occurrences']} failing query(ies)")
        lines.append("")
        if e["expected_doc_ids"]:
            lines.append(f"- **Expected doc id(s):** {', '.join('`' + d + '`' for d in e['expected_doc_ids'])}")
        lines.append(f"- **Candidate sources:** {', '.join('`' + s + '`' for s in e['candidate_sources'])}")
        lines.append("- **Failing queries:**")
        for q in e["failing_queries"]:
            lines.append(f"  - {q}")
        lines.append("- **Draft outline (skeleton — author + source-verify):**")
        lines.append("")
        lines.append("  ```markdown")
        for row in e["draft_outline"]:
            lines.append(f"  {row}")
        lines.append("  ```")
        lines.append("")
    return "\n".join(lines) + "\n"


def render_judge_accuracy_md(acc: JudgeAccuracy) -> str:
    m = acc.misleading
    lines = [
        "# Judge-accuracy report",
        "",
        f"Calibrated on **{acc.n}** human-labeled items · overall agreement **{acc.agreement:.0%}**.",
        "",
        "| verdict | support | precision | recall | f1 |",
        "|---|---:|---:|---:|---:|",
    ]
    for label, cm in acc.per_class.items():
        lines.append(f"| {label} | {cm.support} | {cm.precision:.2f} | {cm.recall:.2f} | {cm.f1:.2f} |")
    lines += [
        "",
        f"**Misleading recall = {m.recall:.0%}** — the share of fluent-but-wrong answers the judge "
        "actually catches. Treat harness `misleading` counts as a *lower bound* scaled by this number.",
    ]
    return "\n".join(lines) + "\n"


def render_flip_md(decision: FlipDecision) -> str:
    d = decision
    verdict = "FLIP → `mcp.rag.hybrid.lexical-enabled=true`" if d.recommend_flip else "HOLD (keep lexical off)"
    return (
        "# Hybrid-lexical flip-threshold evidence\n\n"
        f"**Recommendation: {verdict}**\n\n"
        f"- Dense-missed retrieval misses: **{d.dense_missed}**\n"
        f"- Recovered by lexical alone: **{d.recovered_by_lexical}** ({d.recovery_rate_lexical:.0%})\n"
        f"- Recovered by dense+lexical (RRF hybrid): **{d.recovered_by_hybrid}** ({d.recovery_rate_hybrid:.0%})\n"
        f"- `rag_retrieval.recall_at_k`: **{d.recall_at_k}** (floor {d.recall_floor})\n"
        f"- Criterion: hybrid recovery ≥ {d.min_recovery_rate:.0%} of dense-missed misses AND recall ≥ floor\n\n"
        f"> {d.rationale}\n"
    )

"""Dense-vs-hybrid recovery + the hybrid-lexical flip criterion (§8, phase 4).

The retrieval-miss bucket (taxonomy #2) is the direct signal for #1124's flip-threshold question.
For every retrieval miss, retrieval is run through both dense and dense+lexical(RRF) — the same RRF
fusion as ``eval_live.py``'s ``rag_lexical_hybrid_784`` block — and we record which fusion recovers
the relevant doc. If a meaningful class of misses is recovered by lexical but not dense, that is the
measured evidence to flip ``mcp.rag.hybrid.lexical-enabled=true``.

Flip criterion, concretely: *lexical recovers ≥ X% of the retrieval-miss failures dense does not,
with no regression to ``rag_retrieval.recall_at_k`` below its floor.*
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Iterable, Optional

DEFAULT_RRF_K = 60  # matches HybridRetrievalProperties.rrfK / mcp.rag.hybrid.rrf-k and eval_live.py
DEFAULT_MIN_RECOVERY_RATE = 0.30  # X in the flip criterion; override at call time
DEFAULT_RECALL_FLOOR = 0.85  # EVAL_MIN_RECALL in eval_live.py — no regression below this


def rrf_fuse(ranked_lists: Iterable[list[str]], k: int = DEFAULT_RRF_K, limit: Optional[int] = None) -> list[str]:
    """Reciprocal Rank Fusion of ordered doc-id lists — score(d) = Σ 1/(k + rank_i). Stable sort
    keeps first-seen order among equal scores, matching HybridContentRetriever and eval_live.py."""
    scores: dict[str, float] = {}
    first_seen: list[str] = []
    for ranked in ranked_lists:
        for rank, doc in enumerate(ranked, start=1):
            if doc not in scores:
                first_seen.append(doc)
            scores[doc] = scores.get(doc, 0.0) + 1.0 / (k + rank)
    fused = sorted(first_seen, key=lambda d: scores[d], reverse=True)
    return fused[:limit] if limit is not None else fused


@dataclass
class RecoveryRecord:
    """Per retrieval-miss: did dense / lexical / hybrid surface any expected doc within k?"""

    question_id: str
    expected: list[str]
    dense_hit: bool
    lexical_hit: bool
    hybrid_hit: bool
    tags: list[str] = field(default_factory=list)

    @property
    def recovered_by_lexical(self) -> bool:
        """Dense missed it, lexical alone would have found it — the exact-code/identifier win."""
        return (not self.dense_hit) and self.lexical_hit

    @property
    def recovered_by_hybrid(self) -> bool:
        """Dense missed it, the dense+lexical RRF fusion found it (what the flag actually enables)."""
        return (not self.dense_hit) and self.hybrid_hit

    def to_dict(self) -> dict:
        return {
            "question_id": self.question_id,
            "expected": self.expected,
            "dense_hit": self.dense_hit,
            "lexical_hit": self.lexical_hit,
            "hybrid_hit": self.hybrid_hit,
            "recovered_by_lexical": self.recovered_by_lexical,
            "recovered_by_hybrid": self.recovered_by_hybrid,
            "tags": self.tags,
        }


def recovery_record(
    question_id: str,
    expected: list[str],
    dense: list[str],
    lexical: list[str],
    k: int,
    *,
    rrf_k: int = DEFAULT_RRF_K,
    tags: Optional[list[str]] = None,
) -> RecoveryRecord:
    """Build one recovery record from a miss's dense + lexical retrieval. ``expected`` is the set of
    visible covering docs the answer needed; a "hit" is any expected doc present within top-k."""
    dense_k = dense[:k]
    lexical_k = lexical[:k]
    hybrid_k = rrf_fuse([dense_k, lexical_k], k=rrf_k, limit=k)
    exp = set(expected)
    return RecoveryRecord(
        question_id=question_id,
        expected=list(expected),
        dense_hit=bool(exp & set(dense_k)),
        lexical_hit=bool(exp & set(lexical_k)),
        hybrid_hit=bool(exp & set(hybrid_k)),
        tags=list(tags or []),
    )


@dataclass
class FlipDecision:
    dense_missed: int  # retrieval misses where dense found nothing expected within k
    recovered_by_lexical: int
    recovered_by_hybrid: int
    recovery_rate_lexical: float
    recovery_rate_hybrid: float
    recall_at_k: Optional[float]
    min_recovery_rate: float
    recall_floor: float
    recommend_flip: bool
    rationale: str

    def to_dict(self) -> dict:
        return {
            "dense_missed": self.dense_missed,
            "recovered_by_lexical": self.recovered_by_lexical,
            "recovered_by_hybrid": self.recovered_by_hybrid,
            "recovery_rate_lexical": round(self.recovery_rate_lexical, 4),
            "recovery_rate_hybrid": round(self.recovery_rate_hybrid, 4),
            "rag_retrieval_recall_at_k": self.recall_at_k,
            "criterion": {
                "min_recovery_rate": self.min_recovery_rate,
                "recall_floor": self.recall_floor,
            },
            "recommend_flip": self.recommend_flip,
            "rationale": self.rationale,
        }


def flip_decision(
    records: Iterable[RecoveryRecord],
    *,
    recall_at_k: Optional[float] = None,
    min_recovery_rate: float = DEFAULT_MIN_RECOVERY_RATE,
    recall_floor: float = DEFAULT_RECALL_FLOOR,
) -> FlipDecision:
    """Aggregate recovery records into a documented flip recommendation.

    Flip when: hybrid recovers ≥ ``min_recovery_rate`` of the retrieval misses dense could not, AND
    the gated ``rag_retrieval.recall_at_k`` is at/above its floor (so enabling lexical is not
    masking a recall regression). Recovery rate is measured over the *dense-missed* misses only —
    the misses lexical actually has a chance to help — not diluted by misses dense already caught.
    """
    records = list(records)
    dense_missed = [r for r in records if not r.dense_hit]
    n = len(dense_missed)
    lex = sum(1 for r in dense_missed if r.lexical_hit)
    hyb = sum(1 for r in dense_missed if r.hybrid_hit)
    rate_lex = lex / n if n else 0.0
    rate_hyb = hyb / n if n else 0.0

    recall_ok = recall_at_k is None or recall_at_k >= recall_floor
    rate_ok = n > 0 and rate_hyb >= min_recovery_rate
    recommend = rate_ok and recall_ok

    if n == 0:
        rationale = "no dense-missed retrieval misses observed; no evidence to flip"
    elif not rate_ok:
        rationale = (
            f"hybrid recovered {hyb}/{n} ({rate_hyb:.0%}) of dense-missed misses, "
            f"below the {min_recovery_rate:.0%} flip threshold"
        )
    elif not recall_ok:
        rationale = (
            f"hybrid recovery {rate_hyb:.0%} clears the bar but recall@k {recall_at_k} "
            f"is below the {recall_floor} floor; hold the flag until recall recovers"
        )
    else:
        rationale = (
            f"hybrid recovered {hyb}/{n} ({rate_hyb:.0%}) of dense-missed misses "
            f"(≥ {min_recovery_rate:.0%}) with recall@k {recall_at_k} ≥ {recall_floor}: flip recommended"
        )

    return FlipDecision(
        dense_missed=n,
        recovered_by_lexical=lex,
        recovered_by_hybrid=hyb,
        recovery_rate_lexical=rate_lex,
        recovery_rate_hybrid=rate_hyb,
        recall_at_k=recall_at_k,
        min_recovery_rate=min_recovery_rate,
        recall_floor=recall_floor,
        recommend_flip=recommend,
        rationale=rationale,
    )

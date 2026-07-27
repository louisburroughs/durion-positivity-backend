"""Four-way failure taxonomy (§5) — route each failure to the right fix.

This classifier is the core of the harness. Misclassifying a **retrieval miss** (#2) as a **corpus
gap** (#1) bloats the corpus with duplicate docs and hides retrieval bugs; the distinction requires
both the retrieved-context dump (what actually reached the prompt) and a corpus lookup (does any doc
cover the topic at all).

Decision, given a non-correct answer:

    candidates  = the doc(s) that *should* answer this question
                  (SME-declared expected_doc_ids, else corpus content lookup)
    existing    = candidates that actually exist in the corpus
    retrieved   = doc ids that reached the prompt (permission-filtered dense context)

    no existing candidate ............................... #1 corpus_gap
    a candidate was retrieved, answer still wrong ....... #3 generation
    every covering doc is gated for this actor .......... #4 permission_gating (correct behaviour)
    a visible covering doc exists but wasn't retrieved .. #2 retrieval_miss  -> feeds the flip-threshold
"""

from __future__ import annotations

from typing import Optional

from .corpus import CorpusIndex
from .model import (
    CAUSE_CORPUS_GAP,
    CAUSE_GENERATION,
    CAUSE_NONE,
    CAUSE_PERMISSION_GATING,
    CAUSE_RETRIEVAL_MISS,
    VERDICT_CORRECT,
    Capture,
    Classification,
    Question,
)

# Coverage-lookup threshold: below this idf-overlap score no doc is treated as covering the topic.
COVERAGE_THRESHOLD = 3.0


def _candidate_docs(question: Question, corpus: CorpusIndex) -> list[str]:
    """The doc(s) that should answer this question. Prefer the SME-declared expected docs (even
    ones not yet authored — an unauthored expected doc is exactly a known corpus gap); fall back to
    a content lookup so *unknown* gaps (no SME declaration) are still discoverable."""
    if question.expected_doc_ids:
        return list(question.expected_doc_ids)
    return corpus.covering_docs(question.query, threshold=COVERAGE_THRESHOLD)


def classify(
    question: Question,
    capture: Capture,
    verdict: str,
    corpus: CorpusIndex,
) -> Classification:
    if verdict == VERDICT_CORRECT:
        return Classification(cause=CAUSE_NONE, rationale="answer graded correct")

    candidates = _candidate_docs(question, corpus)
    existing = [d for d in candidates if corpus.exists(d)]
    retrieved = [d.doc_id for d in capture.dense_docs]
    actor_perms = set(capture.permission_codes)

    # #1 corpus gap — nothing in the corpus covers this topic (declared doc unauthored, or the
    # content lookup found no doc). This is the #1124 signal: a doc needs to be written.
    if not existing:
        return Classification(
            cause=CAUSE_CORPUS_GAP,
            rationale=(
                "no existing corpus doc covers this topic"
                + (f" (expected {candidates} not authored)" if question.expected_doc_ids else "")
            ),
            covering_doc_ids=[],
            retrieved_doc_ids=retrieved,
        )

    visible = [d for d in existing if _visible(corpus, d, actor_perms)]
    gated = [d for d in existing if d not in visible]
    retrieved_relevant = [d for d in existing if d in retrieved]

    # #3 generation — the right context WAS retrieved, yet the answer is wrong/misleading/refused.
    # Not a content or retrieval problem: prompt/model.
    if retrieved_relevant:
        return Classification(
            cause=CAUSE_GENERATION,
            rationale=f"relevant context {retrieved_relevant} was retrieved but the answer is not correct",
            covering_doc_ids=existing,
            retrieved_doc_ids=retrieved,
            gated_doc_ids=gated,
        )

    # #4 permission gating — the only docs that cover this topic are ones the actor cannot see, so
    # the doc was correctly filtered out and a refusal/decline is correct behaviour, not a failure.
    if not visible:
        return Classification(
            cause=CAUSE_PERMISSION_GATING,
            rationale=f"covering docs {gated} require permissions the actor lacks; filtering is correct",
            covering_doc_ids=existing,
            retrieved_doc_ids=retrieved,
            gated_doc_ids=gated,
        )

    # #2 retrieval miss — a visible covering doc exists but was not retrieved. Retrieval problem;
    # feeds the dense-vs-hybrid flip-threshold analysis (§8), NOT a content fix.
    return Classification(
        cause=CAUSE_RETRIEVAL_MISS,
        rationale=f"visible covering docs {visible} exist but were not in the retrieved context",
        covering_doc_ids=existing,
        retrieved_doc_ids=retrieved,
        gated_doc_ids=gated,
    )


def _visible(corpus: CorpusIndex, doc_id: str, actor_perms: set[str]) -> bool:
    doc = corpus.get(doc_id)
    return bool(doc and doc.visible_to(actor_perms))

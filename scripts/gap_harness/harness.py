"""Pipeline orchestrator: questions -> capture -> grade -> classify -> recovery records.

The orchestrator is I/O-agnostic. It takes two callables — ``ask`` (question -> answer) and
``retrieve`` (question -> dense/lexical retrieved docs) — plus an optional judge LLM and the corpus
index. The CLI wires live adapters (chat API, DB retriever, Ollama/OpenAI judge); tests wire fakes;
an offline replay wires ``ask``/``retrieve`` that read a raw dump. Same code path either way.
"""

from __future__ import annotations

from typing import Callable, Optional

from .corpus import CorpusIndex
from .fusion import RecoveryRecord, recovery_record
from .judge import ERR_TRANSPORT, LLM, grade as judge_grade
from .model import (
    CAUSE_RETRIEVAL_MISS,
    VERDICT_MISLEADING,
    Capture,
    Grade,
    Question,
    Result,
    RetrievedDoc,
)
from .taxonomy import classify

# ask(question) -> answer text ; may raise, which is captured as a transport error.
AskFn = Callable[[Question], str]
# retrieve(question) -> (dense_docs, lexical_docs)
RetrieveFn = Callable[[Question], tuple[list[RetrievedDoc], list[RetrievedDoc]]]


def ground_truth_for(question: Question, corpus: CorpusIndex) -> list[str]:
    """Assemble the authoritative facts the judge grades against (§4 grounding, in preference
    order): SME ``expected_facts`` first, then the ``_Verified: …_`` module facts of the expected
    docs. Empty for bare usage questions — the judge should then lean conservative."""
    facts: list[str] = list(question.expected_facts)
    for doc_id in question.expected_doc_ids:
        doc = corpus.get(doc_id)
        if doc:
            facts.extend(doc.verified_facts)
    # de-dup preserving order
    seen, out = set(), []
    for f in facts:
        if f not in seen:
            seen.add(f)
            out.append(f)
    return out


def _ask_error(error: Optional[str]) -> Optional[str]:
    """The ``ask:`` segment of a capture error, if the *answer* call itself failed. Distinct from a
    retrieval failure (which leaves the answer intact): a failed ask means no answer was obtained, so
    there is nothing to grade. ``capture_one`` joins segments with ``"; "`` (e.g.
    ``"retrieve: …; ask: …"``), so split on that and match the ask segment."""
    if not error:
        return None
    for segment in error.split("; "):
        if segment.startswith("ask:"):
            return segment
    return None


def capture_one(question: Question, ask: AskFn, retrieve: RetrieveFn) -> Capture:
    """Phase 1: ask via the API + capture retrieved context. Transport/LLM errors are recorded on
    the Capture rather than aborting the run — one bad question must not sink the batch."""
    dense, lexical = [], []
    error = None
    answer = ""
    try:
        dense, lexical = retrieve(question)
    except Exception as e:  # retrieval failure is distinct from an answer failure
        error = f"retrieve: {type(e).__name__}: {e}"
    try:
        answer = ask(question)
    except Exception as e:
        error = (error + "; " if error else "") + f"ask: {type(e).__name__}: {e}"
    return Capture(
        question_id=question.id,
        answer=answer,
        dense_docs=dense,
        lexical_docs=lexical,
        permission_codes=list(question.actor.permission_codes),
        error=error,
    )


def run(
    questions: list[Question],
    ask: AskFn,
    retrieve: RetrieveFn,
    corpus: CorpusIndex,
    judge_llm: Optional[LLM] = None,
    *,
    rrf_k: int = 60,
) -> tuple[list[Result], list[RecoveryRecord]]:
    """Run the full pipeline over a question set. Returns per-question results and the recovery
    records for the retrieval-miss bucket (phase-4 flip-threshold input)."""
    results: list[Result] = []
    recoveries: list[RecoveryRecord] = []
    for q in questions:
        capture = capture_one(q, ask, retrieve)
        ask_err = _ask_error(capture.error)
        if ask_err:
            # The answer call itself failed (timeout / connection / non-2xx), so there is no answer
            # to grade. Do NOT fall through to judge_grade: an empty answer trips the deterministic
            # refusal pre-filter (refusal.py) and would be miscounted as a content `refused` with
            # transport=0 — masking a transport/HTTP failure (e.g. a 404 from a wrong base-url) as a
            # real refusal. Surface it as an ungraded `transport:` failure so the report keeps it out
            # of the graded verdict + taxonomy counts, exactly as a judge transport failure is (#1130).
            grade = Grade(
                verdict=VERDICT_MISLEADING,  # placeholder; judge_error pulls it into the ungraded bucket
                rationale="answer call failed; not graded",
                judge_error=f"{ERR_TRANSPORT} {ask_err}",
            )
        else:
            gt = ground_truth_for(q, corpus)
            grade = judge_grade(q.query, capture.answer, gt, judge_llm)
        classification = classify(q, capture, grade.verdict, corpus)
        results.append(Result(question=q, capture=capture, grade=grade, classification=classification))

        # An ungraded record's verdict is a placeholder, so its retrieval_miss classification is not
        # real evidence — keep it out of the dense-vs-hybrid recovery set that feeds the #1124 flip.
        if classification.cause == CAUSE_RETRIEVAL_MISS and not grade.judge_error:
            expected_visible = [
                d for d in classification.covering_doc_ids if d not in classification.gated_doc_ids
            ]
            recoveries.append(
                recovery_record(
                    question_id=q.id,
                    expected=expected_visible,
                    dense=[d.doc_id for d in capture.dense_docs],
                    lexical=[d.doc_id for d in capture.lexical_docs],
                    k=q.k,
                    rrf_k=rrf_k,
                    tags=list(q.tags),
                )
            )
    return results, recoveries


def grade_only(
    question: Question,
    answer: str,
    corpus: CorpusIndex,
    judge_llm: Optional[LLM] = None,
) -> Grade:
    """Grade a single pre-captured answer (used by the calibration path)."""
    return judge_grade(question.query, answer, ground_truth_for(question, corpus), judge_llm)

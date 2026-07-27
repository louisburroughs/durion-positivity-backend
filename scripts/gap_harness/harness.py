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
from .judge import LLM, grade as judge_grade
from .model import (
    CAUSE_RETRIEVAL_MISS,
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
        gt = ground_truth_for(q, corpus)
        grade = judge_grade(q.query, capture.answer, gt, judge_llm)
        classification = classify(q, capture, grade.verdict, corpus)
        results.append(Result(question=q, capture=capture, grade=grade, classification=classification))

        if classification.cause == CAUSE_RETRIEVAL_MISS:
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

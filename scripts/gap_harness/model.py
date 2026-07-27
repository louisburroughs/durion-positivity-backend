"""Dataclasses shared across the harness pipeline.

The pipeline flows: Question -> Capture (ask + retrieve) -> Grade (judge) -> Classification
(taxonomy) -> report. Every stage is a plain, JSON-serializable record so a run can be dumped raw
(phase 1) and replayed offline through the later phases without re-hitting the live stack.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from typing import Any, Optional


# --- verdict / cause vocabularies (kept as plain strings for JSON round-tripping) -------------
VERDICT_CORRECT = "correct"
VERDICT_REFUSED = "refused"
VERDICT_MISLEADING = "misleading"
VERDICTS = (VERDICT_CORRECT, VERDICT_REFUSED, VERDICT_MISLEADING)

CAUSE_CORPUS_GAP = "corpus_gap"  # taxonomy #1 -> write a new doc (the #1124 goal)
CAUSE_RETRIEVAL_MISS = "retrieval_miss"  # taxonomy #2 -> retrieval problem, feeds the flip-threshold
CAUSE_GENERATION = "generation"  # taxonomy #3 -> prompt/model issue
CAUSE_PERMISSION_GATING = "permission_gating"  # taxonomy #4 -> correct behaviour, not a failure
CAUSE_NONE = "none"  # answer was graded correct; no failure to route
CAUSES = (
    CAUSE_CORPUS_GAP,
    CAUSE_RETRIEVAL_MISS,
    CAUSE_GENERATION,
    CAUSE_PERMISSION_GATING,
    CAUSE_NONE,
)


@dataclass(frozen=True)
class Actor:
    """The identity a question is asked as: retrieval scope + permission gating follow from it."""

    role: str
    permission_codes: tuple[str, ...] = ()

    @staticmethod
    def from_dict(d: dict[str, Any]) -> "Actor":
        return Actor(
            role=d.get("role", "ROLE_USER"),
            permission_codes=tuple(d.get("permission_codes", []) or []),
        )

    def to_dict(self) -> dict[str, Any]:
        return {"role": self.role, "permission_codes": list(self.permission_codes)}


@dataclass(frozen=True)
class Question:
    """One eval question with the ground truth needed to grade and classify it.

    ``expected_facts`` is the SME-written source of truth the grounded judge checks against (§4).
    ``expected_doc_ids`` names the corpus doc(s) that *should* answer it; the classifier uses them,
    together with the retrieved context, to tell a corpus gap (#1) from a retrieval miss (#2).
    """

    id: str
    query: str
    actor: Actor
    rag_scope: str = "master"
    expected_facts: tuple[str, ...] = ()
    expected_doc_ids: tuple[str, ...] = ()
    topic: str = ""
    tags: tuple[str, ...] = ()
    source: str = "synthetic"  # synthetic | usage | fixture
    k: int = 5

    @staticmethod
    def from_dict(d: dict[str, Any]) -> "Question":
        return Question(
            id=d["id"],
            query=d["query"],
            actor=Actor.from_dict(d.get("actor", {})),
            rag_scope=d.get("rag_scope", "master"),
            expected_facts=tuple(d.get("expected_facts", []) or []),
            expected_doc_ids=tuple(d.get("expected_doc_ids", []) or []),
            topic=d.get("topic", ""),
            tags=tuple(d.get("tags", []) or []),
            source=d.get("source", "synthetic"),
            k=int(d.get("k", 5)),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "query": self.query,
            "actor": self.actor.to_dict(),
            "rag_scope": self.rag_scope,
            "expected_facts": list(self.expected_facts),
            "expected_doc_ids": list(self.expected_doc_ids),
            "topic": self.topic,
            "tags": list(self.tags),
            "source": self.source,
            "k": self.k,
        }


@dataclass(frozen=True)
class RetrievedDoc:
    """A doc that reached (or would reach) the prompt: id + cosine score, dense or lexical."""

    doc_id: str
    score: float = 0.0


@dataclass
class Capture:
    """Phase-1 raw capture: the answer plus the retrieved context and the actor's permissions.

    Capturing the retrieved context is mandatory (§3.2) — it is what lets the classifier tell a
    missing doc from a doc that exists but was not retrieved.
    """

    question_id: str
    answer: str
    dense_docs: list[RetrievedDoc] = field(default_factory=list)
    lexical_docs: list[RetrievedDoc] = field(default_factory=list)
    permission_codes: list[str] = field(default_factory=list)
    error: Optional[str] = None  # transport/LLM error, distinct from a refusal answer

    def to_dict(self) -> dict[str, Any]:
        return {
            "question_id": self.question_id,
            "answer": self.answer,
            "dense_docs": [asdict(d) for d in self.dense_docs],
            "lexical_docs": [asdict(d) for d in self.lexical_docs],
            "permission_codes": list(self.permission_codes),
            "error": self.error,
        }

    @staticmethod
    def from_dict(d: dict[str, Any]) -> "Capture":
        return Capture(
            question_id=d["question_id"],
            answer=d.get("answer", ""),
            dense_docs=[RetrievedDoc(**rd) for rd in d.get("dense_docs", [])],
            lexical_docs=[RetrievedDoc(**rd) for rd in d.get("lexical_docs", [])],
            permission_codes=list(d.get("permission_codes", [])),
            error=d.get("error"),
        )


@dataclass
class Grade:
    """Judge output (§4). A ``misleading`` verdict must cite the ground-truth fact it contradicts,
    so the verdict is auditable and not a vibe. ``deterministic`` marks a refusal caught by the
    cheap pre-filter (no judge call spent)."""

    verdict: str
    rationale: str = ""
    cited_ground_truth: str = ""
    deterministic: bool = False
    judge_error: Optional[str] = None

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    @staticmethod
    def from_dict(d: dict[str, Any]) -> "Grade":
        return Grade(
            verdict=d["verdict"],
            rationale=d.get("rationale", ""),
            cited_ground_truth=d.get("cited_ground_truth", ""),
            deterministic=d.get("deterministic", False),
            judge_error=d.get("judge_error"),
        )


@dataclass
class Classification:
    """Four-way taxonomy result for a non-correct answer (§5)."""

    cause: str
    rationale: str = ""
    covering_doc_ids: list[str] = field(default_factory=list)  # docs that *should* answer, if any
    retrieved_doc_ids: list[str] = field(default_factory=list)
    gated_doc_ids: list[str] = field(default_factory=list)  # docs the actor lacks perms for

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class Result:
    """One question's full journey through the pipeline; the unit the reports aggregate over."""

    question: Question
    capture: Capture
    grade: Grade
    classification: Classification

    def to_dict(self) -> dict[str, Any]:
        return {
            "question": self.question.to_dict(),
            "capture": self.capture.to_dict(),
            "grade": self.grade.to_dict(),
            "classification": self.classification.to_dict(),
        }

"""Source-grounded judge (§4) — the hard part.

A *refusal* is caught deterministically (refusal.py). A *misleading* answer is fluent, confident,
and plausible, so it can only be caught against a **source of truth**: the judge is handed the
SME-written ``expected_facts`` (preferred) or the doc's ``_Verified: …_`` module facts, and told to
grade the answer *against those facts only*, never its own priors. A ``misleading`` verdict must
cite the specific ground-truth fact it contradicts, so the verdict is auditable.

The LLM call is injected (``llm: Callable[[str], str]``) so this module is testable offline and
model-agnostic. ``calibrate`` scores the judge against a human-labeled set and reports
precision/recall on the ``misleading`` class — the calibration gate that decides how much to trust
the harness.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from typing import Callable, Iterable, Optional

from .model import (
    VERDICT_CORRECT,
    VERDICT_MISLEADING,
    VERDICT_REFUSED,
    VERDICTS,
    Grade,
)
from .refusal import is_refusal, refusal_match

LLM = Callable[[str], str]

_JUDGE_SYSTEM = """\
You are a strict grader for a retrieval-augmented assistant. Grade the ANSWER against the GROUND
TRUTH facts ONLY. Do not use outside knowledge or your own priors — if a claim is not settled by the
ground truth, do not treat it as wrong.

Return exactly one JSON object, no prose, with keys:
  "verdict": one of "correct", "refused", "misleading"
  "rationale": one sentence explaining the verdict
  "cited_ground_truth": for "misleading", the exact ground-truth fact the answer contradicts;
                        otherwise the fact that supports "correct", or "" for "refused".

Definitions:
- "correct": the answer is consistent with the ground truth and materially answers the question.
- "refused": the answer declines, says it lacks information/permission, or does not attempt an answer.
- "misleading": the answer is confident and plausible but contradicts or misstates a ground-truth
  fact (wrong format, wrong permission, wrong owner, invented identifier, etc.). This is the most
  important class to catch — a fluent wrong answer is worse than a refusal.
"""


def build_judge_prompt(question: str, answer: str, ground_truth: Iterable[str]) -> str:
    facts = "\n".join(f"- {f}" for f in ground_truth if f) or "- (no explicit ground-truth fact provided)"
    return (
        f"{_JUDGE_SYSTEM}\n"
        f"QUESTION:\n{question}\n\n"
        f"GROUND TRUTH (authoritative — grade against these only):\n{facts}\n\n"
        f"ANSWER:\n{answer}\n\n"
        f"JSON:"
    )


def _extract_json(raw: str) -> Optional[dict]:
    """Pull the first JSON object out of a model reply that may be fenced or prefixed."""
    if not raw:
        return None
    fenced = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", raw, re.DOTALL)
    candidate = fenced.group(1) if fenced else None
    if candidate is None:
        start = raw.find("{")
        if start == -1:
            return None
        depth = 0
        for i in range(start, len(raw)):
            if raw[i] == "{":
                depth += 1
            elif raw[i] == "}":
                depth -= 1
                if depth == 0:
                    candidate = raw[start : i + 1]
                    break
    if candidate is None:
        return None
    try:
        obj = json.loads(candidate)
        return obj if isinstance(obj, dict) else None
    except json.JSONDecodeError:
        return None


def parse_verdict(raw: str) -> Grade:
    """Parse a judge reply into a Grade, defensively. An unparseable reply is surfaced as a
    ``judge_error`` (verdict falls back to ``misleading`` — the conservative choice, since silently
    treating a broken judge call as ``correct`` would hide real failures)."""
    obj = _extract_json(raw)
    if obj is None:
        return Grade(
            verdict=VERDICT_MISLEADING,
            rationale="unparseable judge response",
            judge_error=f"could not parse JSON from judge output: {raw[:200]!r}",
        )
    verdict = str(obj.get("verdict", "")).strip().lower()
    if verdict not in VERDICTS:
        return Grade(
            verdict=VERDICT_MISLEADING,
            rationale=f"judge returned unknown verdict {verdict!r}",
            cited_ground_truth=str(obj.get("cited_ground_truth", "")),
            judge_error=f"unknown verdict: {verdict!r}",
        )
    return Grade(
        verdict=verdict,
        rationale=str(obj.get("rationale", "")).strip(),
        cited_ground_truth=str(obj.get("cited_ground_truth", "")).strip(),
    )


def grade(
    question: str,
    answer: str,
    ground_truth: Iterable[str],
    llm: Optional[LLM] = None,
    *,
    prefilter_refusals: bool = True,
) -> Grade:
    """Grade one answer. Deterministic refusal pre-filter first (no judge call spent); otherwise
    invoke the grounded judge. If no ``llm`` is supplied, only the refusal pre-filter runs and a
    non-refusal is returned ungraded (verdict ``correct`` is *not* assumed — an ungraded flag is set
    via ``judge_error`` so the caller never mistakes 'not judged' for 'judged correct')."""
    if prefilter_refusals and is_refusal(answer):
        return Grade(
            verdict=VERDICT_REFUSED,
            rationale=f"deterministic refusal match: {refusal_match(answer)!r}",
            deterministic=True,
        )
    if llm is None:
        return Grade(
            verdict=VERDICT_CORRECT,
            rationale="no judge configured; answer not graded",
            judge_error="ungraded: no llm supplied",
        )
    try:
        raw = llm(build_judge_prompt(question, answer, list(ground_truth)))
    except Exception as e:  # transport / model error — surface, do not swallow into a verdict
        return Grade(
            verdict=VERDICT_MISLEADING,
            rationale="judge call failed",
            judge_error=f"{type(e).__name__}: {e}",
        )
    return parse_verdict(raw)


# --- calibration / judge-accuracy (§4) ---------------------------------------------------------
@dataclass
class ClassMetrics:
    label: str
    support: int
    precision: float
    recall: float
    f1: float


@dataclass
class JudgeAccuracy:
    n: int
    agreement: float  # fraction of items where judge verdict == human verdict
    per_class: dict[str, ClassMetrics]
    confusion: dict[str, dict[str, int]]  # human -> predicted -> count

    @property
    def misleading(self) -> ClassMetrics:
        return self.per_class[VERDICT_MISLEADING]

    def to_dict(self) -> dict:
        return {
            "n": self.n,
            "agreement": round(self.agreement, 4),
            "per_class": {
                k: {
                    "support": m.support,
                    "precision": round(m.precision, 4),
                    "recall": round(m.recall, 4),
                    "f1": round(m.f1, 4),
                }
                for k, m in self.per_class.items()
            },
            "confusion_human_x_predicted": self.confusion,
        }


def calibrate(pairs: Iterable[tuple[str, str]]) -> JudgeAccuracy:
    """Score judge verdicts against human labels. ``pairs`` are ``(predicted, human)`` verdicts.

    Reports overall agreement plus per-class precision/recall/F1, with the ``misleading`` class
    called out — a harness is only as trustworthy as its grader (§4), and ``misleading`` recall is
    the number that says how many fluent-but-wrong answers slip through."""
    pairs = list(pairs)
    n = len(pairs)
    confusion: dict[str, dict[str, int]] = {h: {p: 0 for p in VERDICTS} for h in VERDICTS}
    agree = 0
    for predicted, human in pairs:
        if human in confusion and predicted in confusion[human]:
            confusion[human][predicted] += 1
        if predicted == human:
            agree += 1
    per_class = {}
    for label in VERDICTS:
        tp = confusion.get(label, {}).get(label, 0)
        predicted_total = sum(confusion[h][label] for h in VERDICTS)
        actual_total = sum(confusion[label][p] for p in VERDICTS)
        precision = tp / predicted_total if predicted_total else 0.0
        recall = tp / actual_total if actual_total else 0.0
        f1 = (2 * precision * recall / (precision + recall)) if (precision + recall) else 0.0
        per_class[label] = ClassMetrics(label, actual_total, precision, recall, f1)
    return JudgeAccuracy(
        n=n,
        agreement=agree / n if n else 0.0,
        per_class=per_class,
        confusion=confusion,
    )

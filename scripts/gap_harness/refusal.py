"""Deterministic refusal detection — the cheap pre-filter (§4).

A refusal ("I can't answer that", "I don't have information on…") is a trivial string/intent match
and must NOT cost a judge call. This runs first; only non-refusals go to the grounded judge. Kept
deterministic and conservative: it targets the assistant's own inability-to-answer phrasings, not
answers that merely *mention* not knowing something in passing.
"""

from __future__ import annotations

import re

# Phrasings the assistant uses when it declines / cannot answer. Anchored to first-person assistant
# voice ("I", "I'm", "the assistant") to avoid firing on a real answer that quotes such a phrase.
_REFUSAL_PATTERNS = [
    r"\bi (?:can(?:'|no)?t|am unable to|cannot) (?:help|answer|assist|provide|find|access)\b",
    r"\bi'?m (?:unable|not able) to\b",
    r"\bi (?:don'?t|do not) have (?:access|information|the information|enough information|details|any)\b",
    r"\bi (?:don'?t|do not) have that information\b",
    r"\bno (?:relevant )?(?:information|documentation|documents?|context) (?:was )?(?:found|available|retrieved)\b",
    r"\bi (?:couldn'?t|could not|was(?:n'?t| not) able to) find\b",
    r"\bi'?m sorry,? but i\b",
    r"\bi (?:don'?t|do not) (?:know|have knowledge)\b",
    r"\bthat'?s (?:outside|beyond) (?:my|the) (?:scope|knowledge)\b",
    r"\bi'?m not (?:sure|certain) (?:how|what|where|about)\b",
    r"\b(?:unfortunately|regrettably),? i (?:can'?t|cannot|don'?t|am unable)\b",
    r"\byou (?:don'?t|do not) have (?:permission|the required permission|access)\b",
    r"\bpermission denied\b",
    r"\bnot authori[sz]ed\b",
]

_REFUSAL_RE = re.compile("|".join(_REFUSAL_PATTERNS), re.IGNORECASE)

# An empty / whitespace / trivially short answer is treated as a refusal too (nothing to grade).
_MIN_ANSWER_CHARS = 2


def is_refusal(answer: str) -> bool:
    """True if the answer is a deterministic refusal / inability-to-answer / permission block."""
    if answer is None:
        return True
    stripped = answer.strip()
    if len(stripped) < _MIN_ANSWER_CHARS:
        return True
    return bool(_REFUSAL_RE.search(stripped))


def refusal_match(answer: str) -> str:
    """The matched refusal phrase (for the rationale), or '' if not a refusal. Useful so a refusal
    verdict cites *why* it was classed a refusal rather than being opaque."""
    if answer is None or len(answer.strip()) < _MIN_ANSWER_CHARS:
        return "empty-or-trivial-answer"
    m = _REFUSAL_RE.search(answer.strip())
    return m.group(0) if m else ""

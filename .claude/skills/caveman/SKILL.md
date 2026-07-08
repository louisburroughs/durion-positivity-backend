---
description: Toggle or tune the repo-default caveman output-compression style (terse, token-cheap chat responses). Use when the user says "caveman on/off", "be terse", "verbose mode", or asks about the output style.
---

# Caveman — output compression

Dependency-free adaptation of Caveman (github.com/JuliusBrussee/caveman, unreachable from web
sessions) per the durion `token-stack` skill. The rules live in `CLAUDE.md` ("Output Style —
Caveman") and are **default ON** for chat output in this repo.

## Rules (same as CLAUDE.md)

1. Answer first; no preamble, no restated question, no sign-off.
2. Telegraphic prose. Fragments fine. Filler dropped.
3. Lists/tables over paragraphs; ~1 line per point.
4. Cite `path:line`; never echo file contents, diffs, or logs unasked.
5. One-line status updates. No emoji. No headers on short answers.

## Scope

Chat responses only. Commits, PR/issue bodies, code, comments, docs, ADRs: normal conventions,
full quality. Correctness beats brevity — if compression would blur a nuanced finding, use
normal prose for that part.

## Toggling

- "caveman off" / "verbose" / "explain in detail" → normal prose until the user re-enables or
  the session ends.
- "caveman on" → re-apply the rules above.
- Per-turn override always wins over the default.

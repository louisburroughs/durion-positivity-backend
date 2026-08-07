# Archived docs

Historical designs, plans, and one-shot authoring artifacts whose work has shipped or whose
content was superseded. Kept for provenance; do not treat as current.

| Doc | Why archived |
|---|---|
| `nl-interface-design.md` | Pre-Spring-AI (LangChain4j) master design. Superseded by module `README.md`, `../tool-selection-architecture.md`, and the `gate*` design docs. |
| `gate-verification-runbook.md` | Commands target the retired `feat/nl-interface-gates` worktree and LangChain4j runtime. Live checks that still matter are tracked on the open gates/issues. |
| `spring-ai-issues-delivery-plan.md` | All sequenced issues (#645, #778–#785) delivered; only #645's alpha 404 check remains, tracked on the issue. |
| `mcp-hardening-session-plan.md` | Session executed; all bundled issues closed except #645's live check. |
| `answer-resolution-ladder-design.md` | Implemented (`AnswerResolutionLadder` / `AnswerResolutionLadderImpl`). |
| `rag-hybrid-lexical-784-design.md` | Implemented via #1123 (`LexicalDocumentRetriever`, RRF fusion); `lexical-enabled` now defaults `true`. |
| `rag-corpus-gap-harness-design.md` | Implemented (#1125); `scripts/gap_harness/README.md` is the living doc. |
| `rag-corpus-growth-and-flip-threshold-1124.md` | #1124 closed; flip criterion met — lexical retrieval enabled by default. |
| `rag-corpus-growth-plan-1124.md` | Authoring backlog executed; corpus grew to 39 docs across all six waves. |
| `research-*-anchors.md` (waves 1–6) | Source-anchor research consumed by the wave authoring; the resulting RAG docs are in `src/main/resources/rag/`. |
| `gate5-rag-authoring-prompt.md` / `gate5-rag-SOURCES.md` | One-shot G5.5 authoring prompt + source notes; the 11 docs were authored and shipped. |

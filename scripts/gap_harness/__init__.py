"""RAG corpus gap-discovery harness (issue #1125).

An agent-driven harness that asks realistic questions through the MCP chat API, captures the
retrieved context, grades answers against ground truth with a source-grounded judge, classifies
every failure into a four-way taxonomy, and emits a human-actionable gap report to grow the RAG
corpus systematically. The retrieval-miss bucket doubles as the measured evidence for #1124's
hybrid-lexical flip-threshold.

Design: pos-mcp-server/docs/rag-corpus-gap-harness-design.md
Builds on: scripts/eval_live.py (retrieval reproduction + RRF) and scripts/rag_seed.py (corpus
manifest).

The package is layered so the pure decision logic (refusal detection, grounded-judge parsing,
four-way classification, RRF recovery + flip criterion, report rendering) is import-clean and unit
tested with no live infrastructure, while the I/O adapters (DB retrieval, chat API, judge LLM) sit
behind thin, injectable boundaries so an offline raw dump can drive phases 2-4.
"""

from . import corpus, fusion, judge, model, questions, refusal, report, taxonomy

__all__ = ["corpus", "fusion", "judge", "model", "questions", "refusal", "report", "taxonomy"]

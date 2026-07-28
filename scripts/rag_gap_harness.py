#!/usr/bin/env python3
"""RAG corpus gap-discovery harness — CLI (issue #1125).

Agent-driven harness that asks realistic questions through the MCP chat API, captures the retrieved
context, grades answers with a source-grounded judge, classifies every failure into a four-way
taxonomy, and emits a human-actionable gap report — while measuring dense-vs-hybrid recovery to
answer #1124's hybrid-lexical flip-threshold.

Design: pos-mcp-server/docs/rag-corpus-gap-harness-design.md
Builds on: scripts/eval_live.py (retrieval reproduction + RRF), scripts/rag_seed.py (corpus manifest).

Subcommands:
    run           live: ask via API + reproduce retrieval + grade + classify + report
    replay        offline: re-grade/classify a prior run's raw dump (iterate phases 2-4, no stack)
    calibrate     score the judge against a human-labeled set -> judge-accuracy report (§4 gate)
    emit-fixture  phase-5 round-trip: turn a confirmed+authored gap into an eval_live.py fixture

Live `run`/`calibrate` need a reachable stack (chat API, Postgres/pgvector, embedding + judge model),
exactly like scripts/run-live-eval.sh. `replay` and `emit-fixture` are offline. All pure decision
logic lives in the `gap_harness` package and is unit-tested (scripts/tests/test_gap_harness_*.py).

Usage:
    # Phase 1 capture + phases 2-4 in one live pass:
    POS_MCP_TOKEN_ROLE_ADMIN=... python3 scripts/rag_gap_harness.py run \\
        --base-url http://localhost:8080 --judge ollama --judge-model llama3.1 \\
        --out pos-mcp-server/target/gap-harness

    # Iterate the judge/taxonomy offline on a captured dump:
    python3 scripts/rag_gap_harness.py replay --results pos-mcp-server/target/gap-harness/results.json

    # Judge calibration gate:
    python3 scripts/rag_gap_harness.py calibrate \\
        --calibration pos-mcp-server/src/test/resources/eval/gap-harness/calibration.json --judge ollama
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from gap_harness import api, corpus as corpus_mod, fusion, harness, judge as judge_mod, questions as q_mod, report
from gap_harness.model import Actor, Capture, Grade, Question, Result

ROOT = SCRIPTS.parent
EVAL = ROOT / "pos-mcp-server/src/test/resources/eval"
GAP_QUESTIONS = EVAL / "gap-harness"


# --- shared wiring -----------------------------------------------------------------------------
def build_judge(args):
    timeout = getattr(args, "judge_timeout", 120)
    if args.judge == "none":
        return None
    if args.judge == "ollama":
        base = args.judge_base or os.environ.get("OLLAMA_CHAT_BASE_URL", "http://localhost:11434")
        return api.ollama_judge(base, args.judge_model or "llama3.1", timeout=timeout)
    if args.judge == "openai":
        base = args.judge_base or os.environ.get("OPENAI_BASE_URL", "https://api.openai.com/v1")
        key = os.environ.get("OPENAI_API_KEY")
        return api.openai_compatible_judge(base, args.judge_model or "gpt-4o-mini", key, timeout=timeout)
    raise SystemExit(f"unknown judge backend: {args.judge}")


def load_questions(args) -> list[Question]:
    qs: list[Question] = []
    if GAP_QUESTIONS.is_dir():
        qs += q_mod.load_question_files(sorted(GAP_QUESTIONS.glob("questions*.json")))
    if args.include_fixtures:
        qs += q_mod.from_rag_fixtures([EVAL / "rag-retrieval", EVAL / "rag-lexical"])
    if args.usage:
        rows = json.loads(Path(args.usage).read_text())
        qs += q_mod.from_usage_rows(rows if isinstance(rows, list) else rows.get("rows", []))
    # ground-truth-bearing questions first so dedupe keeps them over bare usage rows
    qs.sort(key=lambda q: 0 if (q.expected_facts or q.expected_doc_ids) else 1)
    qs = q_mod.dedupe(qs)
    if args.limit:
        qs = qs[: args.limit]
    return qs


def write_outputs(out_dir: Path, results: list[Result], recoveries, recall_at_k, judge_acc=None):
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "results.json").write_text(
        json.dumps({"results": [r.to_dict() for r in results]}, indent=2)
    )
    summary = report.summarize(results)
    gap = report.build_gap_report(results)
    decision = fusion.flip_decision([r for r in recoveries], recall_at_k=recall_at_k)
    (out_dir / "summary.json").write_text(json.dumps(summary, indent=2))
    (out_dir / "gap-report.json").write_text(json.dumps(gap, indent=2))
    (out_dir / "gap-report.md").write_text(report.render_gap_report_md(gap))
    (out_dir / "flip-threshold.json").write_text(json.dumps(decision.to_dict(), indent=2))
    (out_dir / "flip-threshold.md").write_text(report.render_flip_md(decision))
    (out_dir / "recovery-records.json").write_text(
        json.dumps([r.to_dict() for r in recoveries], indent=2)
    )
    if judge_acc is not None:
        (out_dir / "judge-accuracy.json").write_text(json.dumps(judge_acc.to_dict(), indent=2))
        (out_dir / "judge-accuracy.md").write_text(report.render_judge_accuracy_md(judge_acc))
    print(json.dumps({"summary": summary, "flip": decision.to_dict(), "out": str(out_dir)}, indent=2))
    return summary


# --- subcommand: run ---------------------------------------------------------------------------
def cmd_run(args):
    from gap_harness.retrieval import DbRetriever

    idx = corpus_mod.load_corpus()
    qs = load_questions(args)
    if not qs:
        raise SystemExit("no questions loaded")
    token_provider = api.env_role_token_provider()
    if args.token:
        token_provider = api.static_token_provider(args.token)
    chat = api.ChatClient(args.base_url, token_provider)
    retriever = DbRetriever.connect()
    judge_llm = build_judge(args)

    def ask(q: Question) -> str:
        return chat.ask(q.actor, q.query)

    def retrieve(q: Question):
        dense = retriever.dense_docs(q.query, q.rag_scope, list(q.actor.permission_codes), q.k)
        try:
            lexical = retriever.lexical_docs(q.query, q.rag_scope, list(q.actor.permission_codes), q.k)
        except Exception:
            lexical = []  # content_tsv absent -> lexical unavailable; flip analysis degrades
        return dense, lexical

    results, recoveries = harness.run(qs, ask, retrieve, idx, judge_llm, rrf_k=args.rrf_k)
    retriever.close()
    write_outputs(Path(args.out), results, recoveries, recall_at_k=args.recall_at_k)


# --- subcommand: replay ------------------------------------------------------------------------
def cmd_replay(args):
    idx = corpus_mod.load_corpus()
    data = json.loads(Path(args.results).read_text())
    judge_llm = build_judge(args)
    results, recoveries = [], []
    from gap_harness.taxonomy import classify
    from gap_harness.model import CAUSE_RETRIEVAL_MISS

    for row in data.get("results", []):
        q = Question.from_dict(row["question"])
        cap = Capture.from_dict(row["capture"])
        if args.judge == "none" and row.get("grade"):
            grade = Grade.from_dict(row["grade"])  # keep stored verdicts, just re-classify
        else:
            grade = harness.grade_only(q, cap.answer, idx, judge_llm)
        classification = classify(q, cap, grade.verdict, idx)
        results.append(Result(question=q, capture=cap, grade=grade, classification=classification))
        # Skip ungraded records: a placeholder verdict must not feed the flip-threshold recovery set.
        if classification.cause == CAUSE_RETRIEVAL_MISS and not grade.judge_error:
            expected_visible = [d for d in classification.covering_doc_ids if d not in classification.gated_doc_ids]
            recoveries.append(
                fusion.recovery_record(
                    q.id, expected_visible,
                    [d.doc_id for d in cap.dense_docs], [d.doc_id for d in cap.lexical_docs],
                    q.k, rrf_k=args.rrf_k, tags=list(q.tags),
                )
            )
    write_outputs(Path(args.out), results, recoveries, recall_at_k=args.recall_at_k)


# --- subcommand: calibrate ---------------------------------------------------------------------
def cmd_calibrate(args):
    idx = corpus_mod.load_corpus()
    judge_llm = build_judge(args)
    data = json.loads(Path(args.calibration).read_text())
    pairs = []
    detail = []
    for item in data.get("items", []):
        q = Question.from_dict(item["question"]) if "question" in item else Question(
            id=item.get("id", "cal"), query=item["query"], actor=Actor.from_dict(item.get("actor", {})),
            expected_facts=tuple(item.get("expected_facts", []) or []),
            expected_doc_ids=tuple(item.get("expected_doc_ids", []) or []),
        )
        human = item["human_verdict"]
        predicted = item.get("predicted_verdict")
        if predicted is None:
            predicted = harness.grade_only(q, item["answer"], idx, judge_llm).verdict
        pairs.append((predicted, human))
        detail.append({"id": q.id, "predicted": predicted, "human": human, "match": predicted == human})
    acc = judge_mod.calibrate(pairs)
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "judge-accuracy.json").write_text(
        json.dumps({"accuracy": acc.to_dict(), "detail": detail}, indent=2)
    )
    (out_dir / "judge-accuracy.md").write_text(report.render_judge_accuracy_md(acc))
    print(report.render_judge_accuracy_md(acc))


# --- subcommand: emit-fixture ------------------------------------------------------------------
def cmd_emit_fixture(args):
    """Phase-5 round-trip: turn a confirmed+authored gap into a rag-retrieval fixture for the
    eval_live.py gate, so the newly-authored doc is guarded going forward (§9.2)."""
    idx = corpus_mod.load_corpus()
    if not idx.exists(args.doc_id):
        print(
            f"WARNING: doc_id '{args.doc_id}' is not yet in the corpus manifest. Author + ingest the "
            "doc (and mirror it in rag_seed.py MANIFEST) before this fixture can pass the gate.",
            file=sys.stderr,
        )
    fixture = {
        "schema_version": 1,
        "fixtures": [
            {
                "fixture_id": args.fixture_id,
                "query": args.query,
                "actor": {"role": args.role, "permission_codes": args.perm},
                "expected": {"doc_ids": [args.doc_id], "k": args.k},
                "rag_scope": args.scope,
                "tags": ["positive", "gap-harness-roundtrip"],
                "notes": f"Round-tripped from a gap-harness corpus-gap finding for topic '{args.topic}'.",
            }
        ],
    }
    dest = Path(args.out)
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(json.dumps(fixture, indent=2) + "\n")
    print(f"wrote {dest}")


def main(argv=None):
    p = argparse.ArgumentParser(description="RAG corpus gap-discovery harness (#1125)")
    sub = p.add_subparsers(dest="cmd", required=True)

    def add_judge_args(sp):
        sp.add_argument("--judge", choices=["none", "ollama", "openai"], default="none")
        sp.add_argument("--judge-base", default=None)
        sp.add_argument("--judge-model", default=None)
        sp.add_argument(
            "--judge-timeout",
            type=int,
            default=120,
            help="per-call judge HTTP timeout in seconds (default 120; raise on CPU-hosted judges, #1129)",
        )

    r = sub.add_parser("run", help="live run against the stack")
    r.add_argument("--base-url", required=True, help="MCP chat API base, e.g. http://localhost:8080")
    r.add_argument("--out", default=str(ROOT / "pos-mcp-server/target/gap-harness"))
    r.add_argument("--token", default=None, help="single bearer token (else POS_MCP_TOKEN_<ROLE> env)")
    r.add_argument("--include-fixtures", action="store_true", help="also draw questions from eval fixtures")
    r.add_argument("--usage", default=None, help="JSON of real-usage rows to seed questions")
    r.add_argument("--limit", type=int, default=0)
    r.add_argument("--rrf-k", type=int, default=fusion.DEFAULT_RRF_K)
    r.add_argument("--recall-at-k", type=float, default=None, help="rag_retrieval.recall_at_k for the flip gate")
    add_judge_args(r)
    r.set_defaults(func=cmd_run)

    rp = sub.add_parser("replay", help="offline re-grade/classify of a prior run")
    rp.add_argument("--results", required=True)
    rp.add_argument("--out", default=str(ROOT / "pos-mcp-server/target/gap-harness-replay"))
    rp.add_argument("--rrf-k", type=int, default=fusion.DEFAULT_RRF_K)
    rp.add_argument("--recall-at-k", type=float, default=None)
    add_judge_args(rp)
    rp.set_defaults(func=cmd_replay)

    c = sub.add_parser("calibrate", help="judge accuracy vs a human-labeled set")
    c.add_argument("--calibration", default=str(GAP_QUESTIONS / "calibration.json"))
    c.add_argument("--out", default=str(ROOT / "pos-mcp-server/target/gap-harness"))
    add_judge_args(c)
    c.set_defaults(func=cmd_calibrate)

    e = sub.add_parser("emit-fixture", help="turn an authored gap into an eval_live fixture")
    e.add_argument("--doc-id", required=True)
    e.add_argument("--query", required=True)
    e.add_argument("--scope", required=True)
    e.add_argument("--role", default="ROLE_USER")
    e.add_argument("--perm", action="append", default=[], help="required permission code (repeatable)")
    e.add_argument("--topic", default="")
    e.add_argument("--fixture-id", default="rag-gap-roundtrip-1")
    e.add_argument("--k", type=int, default=5)
    e.add_argument("--out", required=True)
    e.set_defaults(func=cmd_emit_fixture)

    args = p.parse_args(argv)
    args.func(args)


if __name__ == "__main__":
    main()

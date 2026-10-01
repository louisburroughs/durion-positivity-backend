#!/usr/bin/env python3
"""Re-derives `hard_negative_for` in the ADR-0068 tagging gate fixtures from the heuristic tagger.

`hard_negative_for` lists the tags for which an utterance is a negative (expected false, or
`workflow_state` expected IDLE) that `HeuristicQuestionTagger` answers wrongly (true, or non-IDLE).
It is never edited by hand: run this after a label or text edit, or after a heuristic rule change.

The heuristic is Java (pos-mcp-server, ADR-0068 wave 1, #2367), so its answers come from
scripts/tagging_gate/HeuristicAnswers.java run in source-file mode against a build of that module:

    # in a checkout that has HeuristicQuestionTagger (feat/adr-0068-w1-tagging-seam until it merges)
    ./mvnw -q -pl pos-mcp-server -am -DskipTests -Dspotless.check.skip=true -Dcheckstyle.skip=true \\
        -Dspotbugs.skip=true compile dependency:build-classpath \\
        -Dmdep.outputFile=/tmp/mcp-cp.txt -Dmdep.includeScope=runtime
    # back here; JDK 25
    python3 scripts/derive_tagging_hard_negatives.py \\
        --classpath "$CHECKOUT/pos-mcp-server/target/classes:$(cat /tmp/mcp-cp.txt)" [--check | --write]

Report-only by default: it prints every utterance whose hard_negative_for differs from the
derivation and writes nothing. --write rewrites the fixtures in --gate-dir (the real ones unless
given). --check is the report that exits 1 on any difference (CI-style). --answers FILE reads the
JSON lines HeuristicAnswers.java printed instead of running Java; every answer row must carry
every tag in HARD_NEGATIVE_TAGS, or the run stops (the Java side drifted).
Stdlib only.
"""

import argparse
import json
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
GATE_DIR = ROOT / "pos-mcp-server" / "src" / "test" / "resources" / "eval" / "tagging-gate"
LANGUAGES = ("en", "fr-CA", "es")
JAVA_SOURCE = pathlib.Path(__file__).resolve().parent / "tagging_gate" / "HeuristicAnswers.java"

# The order hard_negative_for lists its tags in: the eight Nouls, then workflow_state.
HARD_NEGATIVE_TAGS = (
    "follows_previous_turn",
    "simple_chat",
    "needs_web_search",
    "about_inventory",
    "about_orders",
    "implies_date_window",
    "admin_account_question",
    "compound_question",
    "workflow_state",
)


def is_negative(tag, expected):
    return expected == "IDLE" if tag == "workflow_state" else expected is False


def heuristic_positive(tag, answer):
    """The heuristic answered the positive side: true, or a workflow state other than IDLE."""
    if answer is None:
        return False
    return answer != "IDLE" if tag == "workflow_state" else str(answer).lower() == "true"


def hard_negatives(expected_tags, answers):
    """The tags the utterance is a negative for and the heuristic gets wrong, in HARD_NEGATIVE_TAGS order."""
    return [tag for tag in HARD_NEGATIVE_TAGS
            if is_negative(tag, expected_tags[tag]) and heuristic_positive(tag, answers.get(tag))]


def load_fixtures(gate_dir=GATE_DIR):
    fixtures = {}
    for language in LANGUAGES:
        path = gate_dir / f"{language}.json"
        fixtures[language] = (path, json.loads(path.read_text(encoding="utf-8")))
    return fixtures


def dump(fixture):
    """The fixtures' own formatting: two-space indent, UTF-8 text, trailing newline."""
    return json.dumps(fixture, indent=2, ensure_ascii=False) + "\n"


def parse_answers(lines):
    answers = {}
    for line in lines:
        line = line.strip()
        if line.startswith("{"):
            record = json.loads(line)
            missing = [tag for tag in HARD_NEGATIVE_TAGS if tag not in record["answers"]]
            if missing:
                raise SystemExit(f"heuristic answers for {record['text']!r} lack {missing}: "
                                 f"HeuristicAnswers.java and HARD_NEGATIVE_TAGS have drifted")
            answers[record["text"]] = record["answers"]
    return answers


def run_java(texts, classpath, java):
    result = subprocess.run(
        [java, "-cp", classpath, str(JAVA_SOURCE)],
        input="\n".join(texts) + "\n", capture_output=True, text=True, encoding="utf-8", check=False)
    if result.returncode != 0:
        sys.stderr.write(result.stderr)
        raise SystemExit(f"{JAVA_SOURCE.name} failed with exit code {result.returncode}")
    # split on "\n" only: splitlines() would also break on U+2028 / U+0085 inside a JSON string
    lines = result.stdout.split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    return parse_answers(lines)


def derive(fixtures, answers):
    """Returns {language: [(id, old, new), ...]} for every utterance whose list changes, updating in place."""
    changes = {}
    for language, (_, fixture) in fixtures.items():
        for utterance in fixture["utterances"]:
            if utterance["text"] not in answers:
                raise SystemExit(f"no heuristic answers for {utterance['id']}: {utterance['text']!r}")
            new = hard_negatives(utterance["expected_tags"], answers[utterance["text"]])
            if new != utterance["hard_negative_for"]:
                changes.setdefault(language, []).append((utterance["id"], utterance["hard_negative_for"], new))
                utterance["hard_negative_for"] = new
    return changes


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--classpath", help="pos-mcp-server classes + runtime classpath of a #2367 build")
    source.add_argument("--answers", help="JSON lines printed by HeuristicAnswers.java")
    parser.add_argument("--java", default="java", help="java launcher (JDK 25)")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="report differences, write nothing, exit 1 on any")
    mode.add_argument("--write", action="store_true", help="rewrite the fixtures in --gate-dir (default: report only)")
    parser.add_argument("--gate-dir", default=str(GATE_DIR), help="directory holding en.json, fr-CA.json, es.json")
    args = parser.parse_args(argv)

    fixtures = load_fixtures(pathlib.Path(args.gate_dir))
    texts = sorted({u["text"] for _, f in fixtures.values() for u in f["utterances"]})
    if args.answers:
        with open(args.answers, encoding="utf-8") as handle:
            answers = parse_answers(handle)
    else:
        answers = run_java(texts, args.classpath, args.java)
    changes = derive(fixtures, answers)
    for language, rows in changes.items():
        for uid, old, new in rows:
            print(f"{uid}: {old} -> {new}")
    if args.check:
        return 1 if changes else 0
    if not args.write:
        print(f"{sum(len(r) for r in changes.values())} utterance(s) differ; nothing written (pass --write)")
        return 0
    for language, (path, fixture) in fixtures.items():
        if language in changes:
            path.write_text(dump(fixture), encoding="utf-8")
    print(f"{sum(len(r) for r in changes.values())} utterance(s) changed")
    return 0


if __name__ == "__main__":
    sys.exit(main())

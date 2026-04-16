#!/usr/bin/env python3
"""Convert emitted pg_dump schema output into a Flyway-ready baseline file."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


DEFAULT_OUTPUT = Path(
    "/home/louis-burroughs/IdeaProjects/durion-positivity-backend/"
    "pos-accounting/src/main/resources/db/baseline-reset/V1__baseline_accounting_schema.sql"
)

KEEP_PREFIXES = (
    "CREATE SEQUENCE",
    "CREATE TABLE",
    "CREATE INDEX",
    "CREATE UNIQUE INDEX",
    "ALTER TABLE",
    "ALTER SEQUENCE",
)

DROP_PREFIXES = (
    "SET ",
    "SELECT pg_catalog.set_config",
    "SELECT pg_catalog.setval",
    "COMMENT ON ",
    "REVOKE ",
    "GRANT ",
    "CREATE EXTENSION ",
)

DROP_CONTAINS = (
    " OWNER TO ",
)


def strip_comments(text: str) -> str:
    return "\n".join(line for line in text.splitlines() if not line.lstrip().startswith("--"))


def split_statements(text: str) -> list[str]:
    statements: list[str] = []
    current: list[str] = []
    in_single_quote = False
    in_double_quote = False
    dollar_tag: str | None = None

    i = 0
    while i < len(text):
        ch = text[i]

        if not in_single_quote and not in_double_quote and ch == "$":
            match = re.match(r"\$[A-Za-z0-9_]*\$", text[i:])
            if match:
                tag = match.group(0)
                current.append(tag)
                i += len(tag)
                if dollar_tag == tag:
                    dollar_tag = None
                elif dollar_tag is None:
                    dollar_tag = tag
                continue

        if dollar_tag is None:
            if ch == "'" and not in_double_quote:
                in_single_quote = not in_single_quote
            elif ch == '"' and not in_single_quote:
                in_double_quote = not in_double_quote

        if ch == ";" and not in_single_quote and not in_double_quote and dollar_tag is None:
            statement = "".join(current).strip()
            if statement:
                statements.append(statement)
            current = []
        else:
            current.append(ch)
        i += 1

    tail = "".join(current).strip()
    if tail:
        statements.append(tail)
    return statements


def normalize_statement(statement: str) -> str:
    statement = re.sub(r"\s+", " ", statement.strip())
    statement = re.sub(r'"public"\.', "", statement, flags=re.IGNORECASE)
    statement = re.sub(r"\bpublic\.", "", statement, flags=re.IGNORECASE)
    statement = re.sub(r"\bONLY\b ", "", statement, flags=re.IGNORECASE)
    return statement.strip()


def should_keep(statement: str) -> bool:
    upper = statement.upper()
    if upper.startswith(DROP_PREFIXES):
        return False
    if any(token in upper for token in DROP_CONTAINS):
        return False
    return upper.startswith(KEEP_PREFIXES)


def build_output(statements: list[str], source_name: str) -> str:
    header = [
        "-- Collapsed baseline for pos-accounting generated from emitted Postgres schema.",
        "-- Source dump: " + source_name,
        "-- Repeatable seed/data migrations remain separate and are not folded into this file.",
        "",
        "SET TIME ZONE 'UTC';",
        "",
    ]

    body = ["{};".format(statement) for statement in statements]
    return "\n\n".join(header + body) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "input",
        nargs="?",
        default="/tmp/pos_accounting_baseline_emitted.sql",
        help="pg_dump schema file to convert",
    )
    parser.add_argument(
        "output",
        nargs="?",
        default=str(DEFAULT_OUTPUT),
        help="target Flyway baseline SQL file",
    )
    args = parser.parse_args()

    input_path = Path(args.input)
    output_path = Path(args.output)

    if not input_path.exists():
        print(f"ERROR: input file not found: {input_path}", file=sys.stderr)
        return 1

    text = strip_comments(input_path.read_text())
    kept_statements: list[str] = []

    for statement in split_statements(text):
        normalized = normalize_statement(statement)
        if should_keep(normalized):
            kept_statements.append(normalized)

    if not kept_statements:
        print("ERROR: no supported DDL statements found in input", file=sys.stderr)
        return 1

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(build_output(kept_statements, str(input_path)))

    print(f"Wrote Flyway baseline candidate: {output_path}")
    print(f"Statements kept: {len(kept_statements)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

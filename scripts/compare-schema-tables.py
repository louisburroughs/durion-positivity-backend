#!/usr/bin/env python3
"""Compare two schema SQL files by table name instead of raw file order."""

from __future__ import annotations

import argparse
import difflib
import re
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path


CREATE_TABLE_RE = re.compile(
    r"^CREATE TABLE(?: IF NOT EXISTS)? (?:(?:public|[A-Za-z0-9_]+)\.)?\"?([A-Za-z0-9_]+)\"?\s*\(",
    re.IGNORECASE | re.DOTALL,
)
CREATE_INDEX_RE = re.compile(
    r"^CREATE (?:UNIQUE )?INDEX(?: IF NOT EXISTS)? \"?([A-Za-z0-9_]+)\"? ON (?:ONLY )?(?:(?:public|[A-Za-z0-9_]+)\.)?\"?([A-Za-z0-9_]+)\"?",
    re.IGNORECASE | re.DOTALL,
)
ALTER_TABLE_RE = re.compile(
    r"^ALTER TABLE(?: ONLY)? (?:(?:public|[A-Za-z0-9_]+)\.)?\"?([A-Za-z0-9_]+)\"?\s+(.*)$",
    re.IGNORECASE | re.DOTALL,
)


@dataclass
class TableSchema:
    columns_and_constraints: list[str] = field(default_factory=list)
    indexes: list[str] = field(default_factory=list)
    alters: list[str] = field(default_factory=list)

    def render(self, name: str) -> list[str]:
        lines = [f"TABLE {name}"]

        lines.append("  COLUMNS_AND_CONSTRAINTS")
        for entry in sorted(self.columns_and_constraints):
            lines.append(f"    {entry}")

        if self.alters:
            lines.append("  ALTERS")
            for entry in sorted(self.alters):
                lines.append(f"    {entry}")

        if self.indexes:
            lines.append("  INDEXES")
            for entry in sorted(self.indexes):
                lines.append(f"    {entry}")

        return lines


def strip_line_comments(text: str) -> str:
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
        nxt = text[i : i + 2]

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


def normalize_sql_fragment(fragment: str) -> str:
    fragment = re.sub(r"\s+", " ", fragment.strip())
    fragment = re.sub(r'"public"\.', "", fragment, flags=re.IGNORECASE)
    fragment = re.sub(r"\bpublic\.", "", fragment, flags=re.IGNORECASE)
    fragment = re.sub(r"\bONLY\b ", "", fragment, flags=re.IGNORECASE)
    return fragment.strip()


def split_top_level_csv(body: str) -> list[str]:
    items: list[str] = []
    current: list[str] = []
    depth = 0
    in_single_quote = False
    in_double_quote = False

    for ch in body:
        if ch == "'" and not in_double_quote:
            in_single_quote = not in_single_quote
        elif ch == '"' and not in_single_quote:
            in_double_quote = not in_double_quote
        elif not in_single_quote and not in_double_quote:
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
            elif ch == "," and depth == 0:
                item = "".join(current).strip()
                if item:
                    items.append(item)
                current = []
                continue
        current.append(ch)

    tail = "".join(current).strip()
    if tail:
        items.append(tail)
    return items


def extract_table_body(statement: str) -> str:
    open_paren = statement.find("(")
    close_paren = statement.rfind(")")
    if open_paren == -1 or close_paren == -1 or close_paren <= open_paren:
        return ""
    return statement[open_paren + 1 : close_paren]


def parse_schema(path: Path) -> dict[str, TableSchema]:
    text = strip_line_comments(path.read_text())
    schemas: dict[str, TableSchema] = defaultdict(TableSchema)

    for statement in split_statements(text):
        stripped = statement.strip()

        table_match = CREATE_TABLE_RE.match(stripped)
        if table_match:
            table_name = table_match.group(1)
            body = extract_table_body(stripped)
            for entry in split_top_level_csv(body):
                schemas[table_name].columns_and_constraints.append(normalize_sql_fragment(entry))
            continue

        index_match = CREATE_INDEX_RE.match(stripped)
        if index_match:
            table_name = index_match.group(2)
            schemas[table_name].indexes.append(normalize_sql_fragment(stripped))
            continue

        alter_match = ALTER_TABLE_RE.match(stripped)
        if alter_match:
            table_name = alter_match.group(1)
            schemas[table_name].alters.append(normalize_sql_fragment(alter_match.group(2)))
            continue

    return dict(schemas)


def build_table_list(
    left_tables: dict[str, TableSchema], right_tables: dict[str, TableSchema], requested: list[str] | None
) -> list[str]:
    if requested:
        return requested
    return sorted(set(left_tables) | set(right_tables))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("left", type=Path, help="Left schema SQL file")
    parser.add_argument("right", type=Path, help="Right schema SQL file")
    parser.add_argument(
        "--tables",
        nargs="+",
        help="Only compare these table names",
    )
    parser.add_argument(
        "--summary-only",
        action="store_true",
        help="Print only missing/changed table summary",
    )
    args = parser.parse_args()

    left_tables = parse_schema(args.left)
    right_tables = parse_schema(args.right)
    table_names = build_table_list(left_tables, right_tables, args.tables)

    missing_left: list[str] = []
    missing_right: list[str] = []
    changed: list[str] = []

    for table_name in table_names:
        left_schema = left_tables.get(table_name)
        right_schema = right_tables.get(table_name)

        if left_schema is None:
            missing_left.append(table_name)
            continue
        if right_schema is None:
            missing_right.append(table_name)
            continue

        left_lines = left_schema.render(table_name)
        right_lines = right_schema.render(table_name)
        if left_lines != right_lines:
            changed.append(table_name)

    if missing_left:
        print("Missing from left:")
        for name in missing_left:
            print(f"  {name}")

    if missing_right:
        print("Missing from right:")
        for name in missing_right:
            print(f"  {name}")

    if changed:
        print("Changed tables:")
        for name in changed:
            print(f"  {name}")

    if args.summary_only:
        return 1 if (missing_left or missing_right or changed) else 0

    for table_name in table_names:
        left_schema = left_tables.get(table_name)
        right_schema = right_tables.get(table_name)
        if left_schema is None or right_schema is None:
            continue

        left_lines = left_schema.render(table_name)
        right_lines = right_schema.render(table_name)
        if left_lines == right_lines:
            continue

        print()
        print(f"=== {table_name} ===")
        for line in difflib.unified_diff(
            left_lines,
            right_lines,
            fromfile=str(args.left),
            tofile=str(args.right),
            lineterm="",
        ):
            print(line)

    return 1 if (missing_left or missing_right or changed) else 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
r"""Sanitize springdoc-generated openapi.yaml files in place.

springdoc emits a number of constructs that are syntactically valid YAML but
semantically invalid OpenAPI 3.x and trip up downstream tooling such as the
openapi-generator Maven plugin. Specifically:

  * `default: ""` on integer/UUID/object schemas — generated as Java field
    initializers like `private Integer x = ;` (compile error) or
    `private UUID id = "";` (wrong type).
  * `default: null` on schemas — meaningless and confuses validators.
  * `additionalProperties:` keys with no value (parsed as null) — fails the
    OpenAPI Generator spec validator (`is not of type \`object\``).
  * Sibling keys next to `$ref` (e.g. `additionalProperties:` alongside a
    `$ref`) — ignored per the OpenAPI/JSON Schema spec but cause validators
    to complain.

This script loads each YAML file, walks the full structure, removes those
problematic entries, and writes the file back. Idempotent.

Usage:
  scripts/sanitize-openapi.py <file> [<file> ...]
"""

from __future__ import annotations

import sys
from pathlib import Path

import yaml


# Structural OpenAPI keys that must be preserved even when empty.
_STRUCTURAL_KEYS = frozenset(
    {
        "paths",
        "components",
        "tags",
        "schemas",
        "responses",
        "parameters",
        "requestBodies",
        "headers",
        "securitySchemes",
        "links",
        "callbacks",
    }
)


def _is_empty_default(value: object) -> bool:
    return value is None or value == ""


def _should_drop_key(key: object, value: object) -> bool:
    if key == "default" and _is_empty_default(value):
        return True
    return key not in _STRUCTURAL_KEYS and (value is None or value == {})


def _clean(node: object) -> object:
    if isinstance(node, dict):
        # If `$ref` is present, all siblings are ignored by the spec — drop
        # them so validators don't complain.
        if "$ref" in node and len(node) > 1:
            node = {"$ref": node["$ref"]}

        cleaned: dict[object, object] = {}
        for key, value in node.items():
            # Drop springdoc placeholder keys that break OpenAPI 3.x validation.
            if _should_drop_key(key, value):
                continue
            cleaned[key] = _clean(value)
        return cleaned
    if isinstance(node, list):
        return [_clean(item) for item in node]
    return node


def sanitize_file(path: Path) -> bool:
    text = path.read_text()
    data = yaml.safe_load(text)
    cleaned = _clean(data)
    new_text = yaml.safe_dump(
        cleaned,
        sort_keys=False,
        default_flow_style=False,
        allow_unicode=True,
        width=4096,
    )
    if new_text == text:
        return False
    path.write_text(new_text)
    return True


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    changed = 0
    for arg in argv[1:]:
        path = Path(arg)
        if not path.is_file():
            print(f"skip (not a file): {path}", file=sys.stderr)
            continue
        if sanitize_file(path):
            print(f"sanitized: {path}")
            changed += 1
        else:
            print(f"unchanged: {path}")
    print(f"done ({changed} file(s) changed)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

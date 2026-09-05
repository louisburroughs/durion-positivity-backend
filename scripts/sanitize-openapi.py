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

Mapping keys are also written in a deterministic order. OpenAPI maps are
semantically unordered, while arrays retain their original order because
array order can be meaningful for fields such as `required`, `enum`, and
`servers`.

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

_TOP_LEVEL_KEY_ORDER = {
    "openapi": 0,
    "info": 1,
    "externalDocs": 2,
    "servers": 3,
    "security": 4,
    "tags": 5,
    "paths": 6,
    "components": 7,
}


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


def _ordered(node: object, *, top_level: bool = False) -> object:
    if isinstance(node, dict):
        if top_level:
            key_order = lambda item: (
                _TOP_LEVEL_KEY_ORDER.get(item[0], len(_TOP_LEVEL_KEY_ORDER)),
                str(item[0]),
            )
        else:
            key_order = lambda item: str(item[0])
        return {
            key: _ordered(value)
            for key, value in sorted(node.items(), key=key_order)
        }
    if isinstance(node, list):
        return [_ordered(item) for item in node]
    return node


class _OpenApiLoader(yaml.SafeLoader):
    """SafeLoader that leaves timestamp-shaped scalars as strings.

    An OpenAPI document has no datetime type: every scalar in it is a string, a
    number, a boolean or null. PyYAML's SafeLoader nonetheless resolves a plain
    scalar such as ``2026-03-17T14:30:00.000Z`` to a ``datetime``, and safe_dump
    then re-emits it in YAML's own timestamp form — ``2026-03-17 14:30:00+00:00``,
    a space instead of the T and the milliseconds gone. Round-tripping a spec
    through this script therefore rewrote ApiError's ``timestamp`` example into a
    value that no longer parses as RFC 3339 and contradicted the description
    directly above it (issue #1764).

    Dropping the implicit timestamp resolver keeps such scalars as ``str``. The
    dumper still has its own resolver, so it quotes them on the way out and a
    later load cannot re-resolve them either.
    """


_OpenApiLoader.yaml_implicit_resolvers = {
    key: [(tag, regexp) for tag, regexp in resolvers if tag != "tag:yaml.org,2002:timestamp"]
    for key, resolvers in yaml.SafeLoader.yaml_implicit_resolvers.items()
}


def sanitize_file(path: Path) -> bool:
    text = path.read_text()
    data = yaml.load(text, Loader=_OpenApiLoader)
    cleaned = _ordered(_clean(data), top_level=True)
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

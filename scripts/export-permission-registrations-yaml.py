#!/usr/bin/env python3
"""
Crawl durion-positivity-backend for *PermissionRegistration classes, verify they
extend PermissionRegistrationSupport, extract PermissionDefinition entries, and
emit YAML.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple


CLASS_DECL_RE = re.compile(
    r"\bclass\s+([A-Za-z_][A-Za-z0-9_]*PermissionRegistration)\b(?:\s+extends\s+([A-Za-z0-9_$.]+))?"
)
PACKAGE_RE = re.compile(r"^\s*package\s+([A-Za-z0-9_.]+)\s*;", re.MULTILINE)
IMPORT_RE = re.compile(r"^\s*import\s+([A-Za-z0-9_.]+)\s*;", re.MULTILINE)
STATIC_IMPORT_RE = re.compile(r"^\s*import\s+static\s+([A-Za-z0-9_.]+)\s*;", re.MULTILINE)
TOP_LEVEL_CLASS_RE = re.compile(r"\b(class|interface|record|enum)\s+([A-Za-z_][A-Za-z0-9_]*)\b")
CONST_RE = re.compile(
    r"\b(?:public|protected|private)?\s*static\s+final\s+String\s+([A-Z0-9_]+)\s*=\s*\"((?:[^\"\\]|\\.)*)\"\s*;"
)


@dataclass
class PermissionEntry:
    name: str
    description: Optional[str]
    raw_name_arg: str
    raw_description_arg: Optional[str]
    name_resolved: bool
    description_resolved: bool


@dataclass
class RegistrationClass:
    class_name: str
    package: Optional[str]
    path: Path
    extends_clause: Optional[str]
    extends_support: bool
    permissions: List[PermissionEntry]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Export PermissionRegistration classes and PermissionDefinition entries as YAML."
        )
    )
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        help="Output YAML file path. Defaults to stdout.",
    )
    parser.add_argument(
        "--strict",
        action="store_true",
        help="Exit non-zero if any PermissionRegistration class does not extend PermissionRegistrationSupport.",
    )
    return parser.parse_args()


def list_java_files(root: Path) -> Iterable[Path]:
    for dirpath, dirnames, filenames in os.walk(root):
        # Prune common non-source dirs for speed.
        dirnames[:] = [
            d for d in dirnames if d not in {".git", ".idea", ".vscode", "target", "build", "node_modules"}
        ]
        for filename in filenames:
            if filename.endswith(".java"):
                yield Path(dirpath) / filename


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="ignore")


def parse_package(source: str) -> Optional[str]:
    match = PACKAGE_RE.search(source)
    return match.group(1) if match else None


def parse_top_level_class_name(source: str) -> Optional[str]:
    match = TOP_LEVEL_CLASS_RE.search(source)
    return match.group(2) if match else None


def build_class_index(root: Path) -> Dict[str, Path]:
    index: Dict[str, Path] = {}
    for java_file in list_java_files(root):
        source = read_text(java_file)
        package_name = parse_package(source)
        class_name = parse_top_level_class_name(source)
        if package_name and class_name:
            index[f"{package_name}.{class_name}"] = java_file
    return index


def parse_string_literal(token: str) -> Optional[str]:
    token = token.strip()
    if len(token) < 2 or not (token.startswith('"') and token.endswith('"')):
        return None
    body = token[1:-1]
    # Minimal Java escape handling for this use-case.
    body = body.replace(r"\\", "\\")
    body = body.replace(r"\"", '"')
    body = body.replace(r"\n", "\n")
    body = body.replace(r"\t", "\t")
    body = body.replace(r"\r", "\r")
    return body


def parse_constants(source: str) -> Dict[str, str]:
    constants: Dict[str, str] = {}
    for match in CONST_RE.finditer(source):
        constants[match.group(1)] = parse_string_literal(f"\"{match.group(2)}\"") or match.group(2)
    return constants


def resolve_static_import_paths(
    source: str, class_index: Dict[str, Path], root: Path
) -> List[Tuple[Optional[Path], Optional[str]]]:
    """
    Returns list of tuples:
      - (path, None) for wildcard static import of class constants
      - (path, CONST_NAME) for single static-imported constant
    """
    resolved: List[Tuple[Optional[Path], Optional[str]]] = []
    for match in STATIC_IMPORT_RE.finditer(source):
        target = match.group(1)
        if target.endswith(".*"):
            class_fqn = target[:-2]
            path = class_index.get(class_fqn)
            if path is None:
                path = root / Path(class_fqn.replace(".", "/") + ".java")
                path = path if path.exists() else None
            resolved.append((path, None))
        else:
            last_dot = target.rfind(".")
            if last_dot < 0:
                continue
            class_fqn = target[:last_dot]
            const_name = target[last_dot + 1 :]
            path = class_index.get(class_fqn)
            if path is None:
                path = root / Path(class_fqn.replace(".", "/") + ".java")
                path = path if path.exists() else None
            resolved.append((path, const_name))
    return resolved


def collect_constant_context(source: str, class_index: Dict[str, Path], root: Path) -> Dict[str, str]:
    constants = parse_constants(source)

    for path, const_name in resolve_static_import_paths(source, class_index, root):
        if path is None:
            continue
        imported_source = read_text(path)
        imported_constants = parse_constants(imported_source)
        if const_name is None:
            constants.update(imported_constants)
        elif const_name in imported_constants:
            constants[const_name] = imported_constants[const_name]

    # Also collect direct imports if PermissionDefinition.of(Class.CONST, ...)
    imported_classes: Dict[str, Path] = {}
    for match in IMPORT_RE.finditer(source):
        fqn = match.group(1)
        if fqn.endswith(".*"):
            continue
        class_name = fqn.split(".")[-1]
        path = class_index.get(fqn)
        if path is None:
            candidate = root / Path(fqn.replace(".", "/") + ".java")
            path = candidate if candidate.exists() else None
        if path:
            imported_classes[class_name] = path

    # Add pseudo namespaced constants like ClassName.CONST for resolution fallback.
    for class_name, path in imported_classes.items():
        imported_constants = parse_constants(read_text(path))
        for key, value in imported_constants.items():
            constants[f"{class_name}.{key}"] = value

    return constants


def split_top_level_args(args: str) -> List[str]:
    parts: List[str] = []
    buf: List[str] = []
    depth = 0
    in_string = False
    escaped = False
    for ch in args:
        if in_string:
            buf.append(ch)
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
            continue

        if ch == '"':
            in_string = True
            buf.append(ch)
            continue
        if ch == "(":
            depth += 1
            buf.append(ch)
            continue
        if ch == ")":
            depth = max(0, depth - 1)
            buf.append(ch)
            continue
        if ch == "," and depth == 0:
            parts.append("".join(buf).strip())
            buf = []
            continue
        buf.append(ch)
    if buf:
        parts.append("".join(buf).strip())
    return [p for p in parts if p]


def capture_parenthesized(source: str, open_paren_idx: int) -> Tuple[str, int]:
    depth = 0
    in_string = False
    escaped = False
    buf: List[str] = []
    i = open_paren_idx
    while i < len(source):
        ch = source[i]
        if in_string:
            buf.append(ch)
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
            i += 1
            continue

        if ch == '"':
            in_string = True
            buf.append(ch)
            i += 1
            continue
        if ch == "(":
            depth += 1
            if depth > 1:
                buf.append(ch)
            i += 1
            continue
        if ch == ")":
            depth -= 1
            if depth == 0:
                return "".join(buf), i
            buf.append(ch)
            i += 1
            continue
        buf.append(ch)
        i += 1
    return "".join(buf), i


def extract_permission_calls(source: str) -> List[List[str]]:
    calls: List[List[str]] = []
    needle = "PermissionDefinition.of("
    start = 0
    while True:
        idx = source.find(needle, start)
        if idx < 0:
            break
        open_paren = idx + len("PermissionDefinition.of")
        args_blob, end_idx = capture_parenthesized(source, open_paren)
        args = split_top_level_args(args_blob)
        if args:
            calls.append(args)
        start = end_idx + 1
    return calls


def resolve_token(token: str, constants: Dict[str, str]) -> Tuple[str, bool]:
    stripped = token.strip()
    literal = parse_string_literal(stripped)
    if literal is not None:
        return literal, True

    if stripped in constants:
        return constants[stripped], True

    # Handle fully-qualified or ClassName.CONSTANT references.
    if "." in stripped:
        tail = stripped.split(".")[-1]
        if tail in constants:
            return constants[tail], True
        if stripped in constants:
            return constants[stripped], True

    return stripped, False


def parse_registration_classes(root: Path, class_index: Dict[str, Path]) -> List[RegistrationClass]:
    registrations: List[RegistrationClass] = []
    for java_file in list_java_files(root):
        source = read_text(java_file)
        class_match = CLASS_DECL_RE.search(source)
        if not class_match:
            continue

        class_name = class_match.group(1)
        extends_clause = class_match.group(2)
        extends_support = extends_clause in {"PermissionRegistrationSupport", "com.positivity.security.common.PermissionRegistrationSupport"}

        constants = collect_constant_context(source, class_index, root)
        permissions: List[PermissionEntry] = []
        for args in extract_permission_calls(source):
            raw_name = args[0]
            raw_description = args[1] if len(args) > 1 else None
            name_value, name_resolved = resolve_token(raw_name, constants)
            description_value: Optional[str]
            description_resolved: bool
            if raw_description is None:
                description_value = None
                description_resolved = True
            else:
                description_value, description_resolved = resolve_token(raw_description, constants)
                if description_value == "null":
                    description_value = None
                    description_resolved = True

            permissions.append(
                PermissionEntry(
                    name=name_value,
                    description=description_value,
                    raw_name_arg=raw_name,
                    raw_description_arg=raw_description,
                    name_resolved=name_resolved,
                    description_resolved=description_resolved,
                )
            )

        registrations.append(
            RegistrationClass(
                class_name=class_name,
                package=parse_package(source),
                path=java_file,
                extends_clause=extends_clause,
                extends_support=extends_support,
                permissions=permissions,
            )
        )

    registrations.sort(key=lambda r: str(r.path))
    return registrations


def yaml_quote(value: str) -> str:
    escaped = value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")
    return f"\"{escaped}\""


def append_yaml_line(lines: List[str], indent: int, text: str) -> None:
    lines.append(("  " * indent) + text)


def build_yaml(root: Path, registrations: List[RegistrationClass]) -> str:
    violations = [r for r in registrations if not r.extends_support]
    permission_count = sum(len(r.permissions) for r in registrations)

    lines: List[str] = []
    append_yaml_line(lines, 0, f'generatedAt: {yaml_quote(datetime.now(timezone.utc).isoformat())}')
    append_yaml_line(lines, 0, f'root: {yaml_quote(str(root))}')
    append_yaml_line(lines, 0, "summary:")
    append_yaml_line(lines, 1, f"registrationClassCount: {len(registrations)}")
    append_yaml_line(lines, 1, f"permissionDefinitionCount: {permission_count}")
    append_yaml_line(lines, 1, f"nonCompliantClassCount: {len(violations)}")
    append_yaml_line(lines, 0, "classes:")

    for reg in registrations:
        append_yaml_line(lines, 1, f"- className: {yaml_quote(reg.class_name)}")
        append_yaml_line(lines, 2, f"package: {yaml_quote(reg.package or '')}")
        append_yaml_line(lines, 2, f"path: {yaml_quote(str(reg.path.relative_to(root)))}")
        append_yaml_line(
            lines,
            2,
            f"extendsPermissionRegistrationSupport: {'true' if reg.extends_support else 'false'}",
        )
        append_yaml_line(lines, 2, f"extendsClause: {yaml_quote(reg.extends_clause or '')}")
        append_yaml_line(lines, 2, "permissions:")
        for perm in reg.permissions:
            append_yaml_line(lines, 3, f"- name: {yaml_quote(perm.name)}")
            if perm.description is None:
                append_yaml_line(lines, 4, "description: null")
            else:
                append_yaml_line(lines, 4, f"description: {yaml_quote(perm.description)}")
            append_yaml_line(lines, 4, f"rawNameArg: {yaml_quote(perm.raw_name_arg)}")
            append_yaml_line(
                lines,
                4,
                f"rawDescriptionArg: {yaml_quote(perm.raw_description_arg or '')}",
            )
            append_yaml_line(lines, 4, f"nameResolved: {'true' if perm.name_resolved else 'false'}")
            append_yaml_line(
                lines,
                4,
                f"descriptionResolved: {'true' if perm.description_resolved else 'false'}",
            )

    append_yaml_line(lines, 0, "violations:")
    if not violations:
        append_yaml_line(lines, 1, "[]")
    else:
        for reg in violations:
            append_yaml_line(lines, 1, f"- className: {yaml_quote(reg.class_name)}")
            append_yaml_line(lines, 2, f"path: {yaml_quote(str(reg.path.relative_to(root)))}")
            append_yaml_line(lines, 2, f"extendsClause: {yaml_quote(reg.extends_clause or '')}")

    return "\n".join(lines) + "\n"


def main() -> int:
    args = parse_args()
    root = Path(__file__).resolve().parents[1]
    class_index = build_class_index(root)
    registrations = parse_registration_classes(root, class_index)
    yaml_output = build_yaml(root, registrations)

    if args.output:
        output_path = args.output if args.output.is_absolute() else (Path.cwd() / args.output)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(yaml_output, encoding="utf-8")
    else:
        sys.stdout.write(yaml_output)

    non_compliant = any(not reg.extends_support for reg in registrations)
    if args.strict and non_compliant:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

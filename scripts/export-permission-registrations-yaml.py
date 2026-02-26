#!/usr/bin/env python3
"""
Aggregate all module permissions.yaml manifests into one YAML report.
"""

from __future__ import annotations

import argparse
import os
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple

try:
    import yaml  # type: ignore
except Exception:  # pragma: no cover
    yaml = None


PRUNE_DIRS = {".git", ".idea", ".vscode", "target", "build", "node_modules", ".mvn"}


@dataclass
class PermissionItem:
    name: str
    description: Optional[str]


@dataclass
class PermissionManifest:
    module: str
    path: Path
    domain: str
    service_name: str
    version: str
    permissions: List[PermissionItem]


@dataclass
class ParseIssue:
    path: Path
    message: str


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Consolidate all permissions.yaml files into one aggregate YAML report."
    )
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        help="Output YAML file path. Defaults to stdout.",
    )
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="Project root to scan. Defaults to repository root.",
    )
    parser.add_argument(
        "--strict",
        action="store_true",
        help="Exit non-zero if parse issues or duplicate permission names are found.",
    )
    return parser.parse_args()


def list_permission_manifests(root: Path) -> Iterable[Path]:
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in PRUNE_DIRS]
        for filename in filenames:
            if filename == "permissions.yaml":
                yield Path(dirpath) / filename


def parse_scalar(value: object) -> Optional[str]:
    if value is None:
        return None
    if isinstance(value, str):
        text = value.strip()
        if text.lower() == "null":
            return None
        return text
    return str(value).strip()


def parse_manifest_with_yaml(path: Path) -> Tuple[Optional[PermissionManifest], Optional[str]]:
    text = path.read_text(encoding="utf-8", errors="ignore")
    data = yaml.safe_load(text)  # type: ignore[attr-defined]
    if not isinstance(data, dict):
        return None, "manifest root must be a map"

    domain = parse_scalar(data.get("domain"))
    service_name = parse_scalar(data.get("serviceName"))
    version = parse_scalar(data.get("version")) or "1.0"
    if not domain:
        return None, "missing required key: domain"
    if not service_name:
        return None, "missing required key: serviceName"

    permissions_node = data.get("permissions")
    if not isinstance(permissions_node, list):
        return None, "missing required key: permissions (list)"

    permissions: List[PermissionItem] = []
    for idx, item in enumerate(permissions_node, start=1):
        if not isinstance(item, dict):
            return None, f"permissions[{idx}] must be a map"
        name = parse_scalar(item.get("name"))
        if not name:
            return None, f"permissions[{idx}] missing required key: name"
        description = parse_scalar(item.get("description"))
        permissions.append(PermissionItem(name=name, description=description))

    module = infer_module_name(path)
    return PermissionManifest(
        module=module,
        path=path,
        domain=domain,
        service_name=service_name,
        version=version,
        permissions=permissions,
    ), None


def parse_value_from_line(raw: str) -> Optional[str]:
    text = raw.strip()
    if not text:
        return ""
    if text.startswith('"') and text.endswith('"') and len(text) >= 2:
        body = text[1:-1]
        body = body.replace(r"\\", "\\")
        body = body.replace(r"\"", '"')
        body = body.replace(r"\n", "\n")
        body = body.replace(r"\t", "\t")
        body = body.replace(r"\r", "\r")
        return body
    if text.startswith("'") and text.endswith("'") and len(text) >= 2:
        return text[1:-1]
    if text.lower() == "null":
        return None
    return text


def parse_manifest_fallback(path: Path) -> Tuple[Optional[PermissionManifest], Optional[str]]:
    text = path.read_text(encoding="utf-8", errors="ignore")
    domain: Optional[str] = None
    service_name: Optional[str] = None
    version: Optional[str] = None
    permissions: List[PermissionItem] = []

    in_permissions = False
    current_item: Dict[str, Optional[str]] = {}

    def flush_item() -> Optional[str]:
        nonlocal current_item
        if not current_item:
            return None
        name = (current_item.get("name") or "").strip()
        if not name:
            return "permission entry missing required key: name"
        permissions.append(
            PermissionItem(
                name=name,
                description=current_item.get("description"),
            )
        )
        current_item = {}
        return None

    for line_no, line in enumerate(text.splitlines(), start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue

        indent = len(line) - len(line.lstrip(" "))

        if indent == 0:
            if in_permissions:
                err = flush_item()
                if err:
                    return None, f"line {line_no}: {err}"
            in_permissions = False

            if stripped == "permissions:":
                in_permissions = True
                continue

            if ":" not in stripped:
                return None, f"line {line_no}: invalid key/value entry"
            key, raw_value = stripped.split(":", 1)
            key = key.strip()
            value = parse_value_from_line(raw_value)
            if key == "domain":
                domain = value or None
            elif key == "serviceName":
                service_name = value or None
            elif key == "version":
                version = value or None
            continue

        if not in_permissions:
            continue

        if stripped.startswith("- "):
            err = flush_item()
            if err:
                return None, f"line {line_no}: {err}"
            rest = stripped[2:].strip()
            if rest:
                if ":" not in rest:
                    return None, f"line {line_no}: invalid permission list item"
                key, raw_value = rest.split(":", 1)
                current_item[key.strip()] = parse_value_from_line(raw_value)
            continue

        if ":" not in stripped:
            return None, f"line {line_no}: invalid permission attribute"
        key, raw_value = stripped.split(":", 1)
        current_item[key.strip()] = parse_value_from_line(raw_value)

    if in_permissions:
        err = flush_item()
        if err:
            return None, err

    if not domain:
        return None, "missing required key: domain"
    if not service_name:
        return None, "missing required key: serviceName"
    if not version:
        version = "1.0"

    module = infer_module_name(path)
    return PermissionManifest(
        module=module,
        path=path,
        domain=domain,
        service_name=service_name,
        version=version,
        permissions=permissions,
    ), None


def parse_manifest(path: Path) -> Tuple[Optional[PermissionManifest], Optional[str]]:
    if yaml is not None:
        return parse_manifest_with_yaml(path)
    return parse_manifest_fallback(path)


def infer_module_name(path: Path) -> str:
    for part in path.parts:
        if part.startswith("pos-"):
            return part
    return path.parent.name


def yaml_quote(value: str) -> str:
    escaped = value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")
    return f"\"{escaped}\""


def append_yaml_line(lines: List[str], indent: int, text: str) -> None:
    lines.append(("  " * indent) + text)


def build_report_yaml(root: Path, manifests: List[PermissionManifest], issues: List[ParseIssue]) -> str:
    flattened: List[Tuple[PermissionManifest, PermissionItem]] = []
    for manifest in manifests:
        for permission in manifest.permissions:
            flattened.append((manifest, permission))

    by_permission: Dict[str, List[Tuple[PermissionManifest, PermissionItem]]] = {}
    for manifest, permission in flattened:
        by_permission.setdefault(permission.name, []).append((manifest, permission))
    duplicates = {k: v for k, v in by_permission.items() if len(v) > 1}

    lines: List[str] = []
    append_yaml_line(lines, 0, f'generatedAt: {yaml_quote(datetime.now(timezone.utc).isoformat())}')
    append_yaml_line(lines, 0, f'root: {yaml_quote(str(root))}')
    append_yaml_line(lines, 0, "summary:")
    append_yaml_line(lines, 1, f"manifestCount: {len(manifests)}")
    append_yaml_line(lines, 1, f"permissionCount: {len(flattened)}")
    append_yaml_line(lines, 1, f"uniquePermissionCount: {len(by_permission)}")
    append_yaml_line(lines, 1, f"duplicatePermissionNameCount: {len(duplicates)}")
    append_yaml_line(lines, 1, f"parseIssueCount: {len(issues)}")

    append_yaml_line(lines, 0, "manifests:")
    for manifest in manifests:
        append_yaml_line(lines, 1, f"- module: {yaml_quote(manifest.module)}")
        append_yaml_line(lines, 2, f"path: {yaml_quote(str(manifest.path.relative_to(root)))}")
        append_yaml_line(lines, 2, f"domain: {yaml_quote(manifest.domain)}")
        append_yaml_line(lines, 2, f"serviceName: {yaml_quote(manifest.service_name)}")
        append_yaml_line(lines, 2, f"version: {yaml_quote(manifest.version)}")
        append_yaml_line(lines, 2, f"permissionCount: {len(manifest.permissions)}")
        append_yaml_line(lines, 2, "permissions:")
        for permission in manifest.permissions:
            append_yaml_line(lines, 3, f"- name: {yaml_quote(permission.name)}")
            if permission.description is None:
                append_yaml_line(lines, 4, "description: null")
            else:
                append_yaml_line(lines, 4, f"description: {yaml_quote(permission.description)}")

    append_yaml_line(lines, 0, "permissions:")
    for manifest, permission in sorted(
        flattened,
        key=lambda x: (x[1].name, x[0].module, str(x[0].path)),
    ):
        append_yaml_line(lines, 1, f"- name: {yaml_quote(permission.name)}")
        if permission.description is None:
            append_yaml_line(lines, 2, "description: null")
        else:
            append_yaml_line(lines, 2, f"description: {yaml_quote(permission.description)}")
        append_yaml_line(lines, 2, f"domain: {yaml_quote(manifest.domain)}")
        append_yaml_line(lines, 2, f"serviceName: {yaml_quote(manifest.service_name)}")
        append_yaml_line(lines, 2, f"module: {yaml_quote(manifest.module)}")
        append_yaml_line(lines, 2, f"source: {yaml_quote(str(manifest.path.relative_to(root)))}")

    append_yaml_line(lines, 0, "duplicates:")
    if not duplicates:
        append_yaml_line(lines, 1, "[]")
    else:
        for permission_name in sorted(duplicates.keys()):
            append_yaml_line(lines, 1, f"- name: {yaml_quote(permission_name)}")
            append_yaml_line(lines, 2, "occurrences:")
            for manifest, permission in sorted(
                duplicates[permission_name],
                key=lambda x: (x[0].module, str(x[0].path)),
            ):
                append_yaml_line(lines, 3, f"- module: {yaml_quote(manifest.module)}")
                append_yaml_line(lines, 4, f"path: {yaml_quote(str(manifest.path.relative_to(root)))}")
                append_yaml_line(lines, 4, f"domain: {yaml_quote(manifest.domain)}")
                append_yaml_line(lines, 4, f"serviceName: {yaml_quote(manifest.service_name)}")
                if permission.description is None:
                    append_yaml_line(lines, 4, "description: null")
                else:
                    append_yaml_line(lines, 4, f"description: {yaml_quote(permission.description)}")

    append_yaml_line(lines, 0, "parseIssues:")
    if not issues:
        append_yaml_line(lines, 1, "[]")
    else:
        for issue in sorted(issues, key=lambda i: str(i.path)):
            append_yaml_line(lines, 1, f"- path: {yaml_quote(str(issue.path.relative_to(root)))}")
            append_yaml_line(lines, 2, f"message: {yaml_quote(issue.message)}")

    return "\n".join(lines) + "\n"


def main() -> int:
    args = parse_args()
    root = args.root.resolve()

    manifests: List[PermissionManifest] = []
    issues: List[ParseIssue] = []

    for manifest_path in sorted(list_permission_manifests(root)):
        parsed, error = parse_manifest(manifest_path)
        if error:
            issues.append(ParseIssue(path=manifest_path, message=error))
            continue
        if parsed:
            manifests.append(parsed)

    report_yaml = build_report_yaml(root, manifests, issues)
    if args.output:
        output_path = args.output if args.output.is_absolute() else (Path.cwd() / args.output)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(report_yaml, encoding="utf-8")
    else:
        sys.stdout.write(report_yaml)

    duplicate_count = 0
    seen: Dict[str, int] = {}
    for manifest in manifests:
        for permission in manifest.permissions:
            seen[permission.name] = seen.get(permission.name, 0) + 1
    duplicate_count = sum(1 for count in seen.values() if count > 1)

    if args.strict and (issues or duplicate_count > 0):
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

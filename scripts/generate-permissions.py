#!/usr/bin/env python3
"""
Regenerates src/main/resources/permissions.yaml for each module by scanning
@PreAuthorize annotations in Java source for hasAuthority / hasAnyAuthority calls.

Only permissions belonging to the module's own domain are written to its YAML.
Cross-domain references (e.g. pos-workorder using inventory:pick_list:view) are
logged as informational warnings but are not added to the file.

Usage:
    python3 scripts/generate-permissions.py ROOT_DIR [module ...] [--dry-run] [--check]
"""

import argparse
import re
import sys
from pathlib import Path

# Matches single-quoted lowercase permission strings: 'a:b' or 'a:b:c'
# Allows letters, digits, underscores, and hyphens in each segment.
PERM_RE = re.compile(
    r"'([a-z][a-z0-9_-]+:[a-z][a-z0-9_-]+(?::[a-z][a-z0-9_-]+)?)'"
)


def extract_preauthorize_blocks(text: str) -> list[str]:
    """Return the inner content of every @PreAuthorize(...) found in text."""
    blocks = []
    pos = 0
    while True:
        m = re.search(r"@PreAuthorize\s*\(", text[pos:])
        if not m:
            break
        start = pos + m.end()
        depth, j = 1, start
        while j < len(text) and depth:
            if text[j] == "(":
                depth += 1
            elif text[j] == ")":
                depth -= 1
            j += 1
        blocks.append(text[start : j - 1])
        pos = j
    return blocks


def scan_module(module_path: Path, domain: str) -> tuple[set[str], set[str]]:
    """
    Scan all .java files under <module>/src/main/java.
    Returns (own_perms, cross_domain_perms).
    """
    java_root = module_path / "src" / "main" / "java"
    own: set[str] = set()
    cross: set[str] = set()
    if not java_root.exists():
        return own, cross
    for java_file in java_root.rglob("*.java"):
        text = java_file.read_text(encoding="utf-8", errors="replace")
        for block in extract_preauthorize_blocks(text):
            for m in PERM_RE.finditer(block):
                perm = m.group(1)
                if perm.startswith("ROLE_"):
                    continue
                if domain and perm.startswith(domain + ":"):
                    own.add(perm)
                else:
                    cross.add(perm)
    return own, cross


def default_description(perm: str) -> str:
    parts = perm.split(":")
    if len(parts) == 3:
        _, resource, action = parts
        return f"{action.replace('_', ' ').replace('-', ' ').capitalize()} {resource.replace('_', ' ').replace('-', ' ')}"
    elif len(parts) == 2:
        domain, action = parts
        return f"{action.replace('_', ' ').replace('-', ' ').capitalize()} {domain.replace('_', ' ').replace('-', ' ')}"
    return perm


def load_existing_yaml(yaml_path: Path) -> dict:
    try:
        import yaml
    except ImportError:
        print("ERROR: PyYAML required. Install with: pip install pyyaml", file=sys.stderr)
        sys.exit(1)
    with yaml_path.open(encoding="utf-8") as f:
        return yaml.safe_load(f) or {}


def write_permissions_yaml(
    path: Path,
    domain: str,
    service_name: str,
    version: str,
    permissions: list[dict],
) -> None:
    lines = [
        f"domain: {domain}",
        f"serviceName: {service_name}",
        f'version: "{version}"',
        "permissions:",
    ]
    for p in permissions:
        desc = p.get("description", "") or ""
        desc = desc.replace("\\", "\\\\").replace('"', '\\"')
        lines.append(f'  - name: "{p["name"]}"')
        lines.append(f'    description: "{desc}"')
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def process_module(
    module_path: Path, dry_run: bool, check: bool
) -> dict | None:
    yaml_path = module_path / "src" / "main" / "resources" / "permissions.yaml"
    if not yaml_path.exists():
        return None

    existing = load_existing_yaml(yaml_path)
    domain: str = existing.get("domain", "")
    service_name: str = existing.get("serviceName", module_path.name)
    version: str = str(existing.get("version", "1.0"))

    existing_descs: dict[str, str] = {
        e["name"]: e.get("description", "")
        for e in existing.get("permissions", [])
        if isinstance(e, dict) and "name" in e
    }

    own_perms, cross_perms = scan_module(module_path, domain)

    added = own_perms - existing_descs.keys()
    # Additive-only: never remove existing entries. Permissions may be enforced
    # via programmatic authority checks (e.g. authorities.contains()) that are
    # invisible to static @PreAuthorize scanning.
    merged_perms = {**{name: default_description(name) for name in own_perms}, **existing_descs}
    changed = bool(added)

    new_permissions = sorted(
        [{"name": name, "description": desc} for name, desc in merged_perms.items()],
        key=lambda p: p["name"],
    )

    if not dry_run and not check and changed:
        write_permissions_yaml(yaml_path, domain, service_name, version, new_permissions)

    return {
        "module": module_path.name,
        "added": sorted(added),
        "cross_domain": sorted(cross_perms),
        "changed": changed,
    }


def discover_modules(root: Path) -> list[Path]:
    # permissions.yaml lives at <module>/src/main/resources/permissions.yaml
    # so 4 .parent calls from the file path yields the module root
    return sorted(
        p.parent.parent.parent.parent
        for p in root.glob("pos-*/src/main/resources/permissions.yaml")
    )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Regenerate permissions.yaml from @PreAuthorize annotations"
    )
    parser.add_argument("root", help="Repository root directory")
    parser.add_argument(
        "modules",
        nargs="*",
        help="Module names relative to root (default: all with permissions.yaml)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print changes without writing files",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="Exit non-zero if any permissions.yaml would change (CI mode)",
    )
    args = parser.parse_args()

    root = Path(args.root).resolve()

    if args.modules:
        module_paths = [root / m for m in args.modules]
    else:
        module_paths = discover_modules(root)

    any_changed = False
    for module_path in module_paths:
        result = process_module(module_path, args.dry_run, args.check)
        if result is None:
            continue

        module_name = result["module"]
        if result["changed"]:
            any_changed = True
            prefix = "(dry-run) " if args.dry_run else ""
            print(f"{prefix}{module_name}:")
            for p in result["added"]:
                print(f"  + {p}")
        else:
            print(f"{module_name}: up-to-date")

        if result["cross_domain"]:
            print(
                f"  (cross-domain refs not written: {', '.join(result['cross_domain'])})"
            )

    if args.check and any_changed:
        print(
            "\nERROR: One or more permissions.yaml files are out of date.",
            file=sys.stderr,
        )
        print(
            "Run scripts/generate-permissions.sh to regenerate them.",
            file=sys.stderr,
        )
        sys.exit(1)


if __name__ == "__main__":
    main()

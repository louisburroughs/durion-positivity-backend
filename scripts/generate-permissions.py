#!/usr/bin/env python3
"""
Regenerates src/main/resources/permissions.yaml for each module by scanning
@PreAuthorize annotations in Java source for hasAuthority / hasAnyAuthority calls.

Only permissions belonging to the module's own domain are written to its YAML.
Cross-domain references (e.g. pos-workorder using inventory:pick_list:view) are
logged as informational warnings but are not added to the file.

With --sync, also updates PermissionCode.java and GatewayPermissionCatalog.java
by appending any @PreAuthorize permissions not yet registered as bit-indexed
enum constants, and bumps CATALOG_VERSION in both files.

Usage:
    python3 scripts/generate-permissions.py ROOT_DIR [module ...] [--dry-run] [--check] [--sync]
"""

import argparse
import re
import sys
from pathlib import Path

# Matches single-quoted permission strings: 'a:b' or 'a:b:c'
# Segments start with a lowercase letter and may contain letters (mixed case),
# digits, underscores, and hyphens — e.g. 'workorder:operationalContext:override'.
PERM_RE = re.compile(
    r"'([a-z][a-zA-Z0-9_-]+:[a-z][a-zA-Z0-9_-]+(?::[a-z][a-zA-Z0-9_-]+)?)'"
)
ENUM_ENTRY_RE = re.compile(r'\((\d+),\s*"([^"]+)"\)')

PERMISSION_CODE_RELPATH = (
    "pos-security-service/src/main/java/com/positivity/securityservice"
    "/internal/enums/PermissionCode.java"
)
GATEWAY_CATALOG_RELPATH = (
    "pos-api-gateway/src/main/java/com/positivity/gateway/config"
    "/GatewayPermissionCatalog.java"
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


# ──────────────────────────────────────────────────────────────────────────────
# Catalog sync: keep PermissionCode.java and GatewayPermissionCatalog.java in
# step with @PreAuthorize annotations without manual bit-index bookkeeping.
# ──────────────────────────────────────────────────────────────────────────────

def scan_all_preauthorize(root: Path) -> set[str]:
    """Collect every permission string from @PreAuthorize across all pos-* modules."""
    all_perms: set[str] = set()
    for java_root in sorted(root.glob("pos-*/src/main/java")):
        for java_file in java_root.rglob("*.java"):
            text = java_file.read_text(encoding="utf-8", errors="replace")
            for block in extract_preauthorize_blocks(text):
                for m in PERM_RE.finditer(block):
                    perm = m.group(1)
                    if not perm.startswith("ROLE_"):
                        all_perms.add(perm)
    return all_perms


def parse_permission_code_java(root: Path) -> tuple[set[str], int]:
    """Return (registered_codes, max_bit_index) from PermissionCode.java."""
    text = (root / PERMISSION_CODE_RELPATH).read_text(encoding="utf-8")
    entries = ENUM_ENTRY_RE.findall(text)
    codes = {code for _, code in entries}
    max_bit = max((int(bit) for bit, _ in entries), default=-1)
    return codes, max_bit


def perm_to_enum_name(perm: str) -> str:
    """'domain:resource:action' → 'DOMAIN__RESOURCE__ACTION'."""
    return perm.replace("-", "_").replace(":", "__").upper()


def _bump_catalog_version(text: str) -> tuple[str, int]:
    holder: list[int] = []

    def repl(m: re.Match) -> str:
        v = int(m.group(1)) + 1
        holder.append(v)
        return f"public static final int CATALOG_VERSION = {v};"

    new_text = re.sub(
        r"public static final int CATALOG_VERSION = (\d+);", repl, text
    )
    if not holder:
        raise ValueError("CATALOG_VERSION constant not found")
    return new_text, holder[0]


def sync_permission_code_java(
    root: Path, new_perms: list[str], next_bit: int, dry_run: bool
) -> int:
    """Append new_perms to PermissionCode.java, bump CATALOG_VERSION, return new version."""
    java_path = root / PERMISSION_CODE_RELPATH
    text = java_path.read_text(encoding="utf-8")

    by_domain: dict[str, list[str]] = {}
    for perm in new_perms:
        domain = perm.split(":")[0]
        by_domain.setdefault(domain, []).append(perm)

    entry_lines: list[str] = []
    for domain in sorted(by_domain):
        display = domain.replace("-", " ").replace("_", " ").title()
        bar = "─" * max(0, 64 - len(display))
        entry_lines.append(f"\n    // ── {display} (new) {bar}─")
        for perm in sorted(by_domain[domain]):
            enum_name = perm_to_enum_name(perm)
            entry_lines.append(f'    {enum_name}({next_bit}, "{perm}"),')
            next_bit += 1

    # Last entry ends with ; not ,
    entry_lines[-1] = entry_lines[-1][:-1] + ";"
    new_block = "\n".join(entry_lines)

    # Insertion point: the last enum constant's ); is immediately followed by
    # a blank line and the CATALOG_VERSION javadoc. Change ); to ), and insert.
    pattern = re.compile(
        r"(\(\d+,\s*\"[^\"]+\"\));(\s*\n\s*\n\s*/\*\*\s*\n\s*\*\s*Current catalog version)",
        re.DOTALL,
    )
    if not pattern.search(text):
        raise ValueError("Cannot find insertion point in PermissionCode.java")
    new_text = pattern.sub(r"\1," + new_block + r"\2", text)

    new_text, new_version = _bump_catalog_version(new_text)
    if not dry_run:
        java_path.write_text(new_text, encoding="utf-8")
    return new_version


def sync_gateway_catalog_java(
    root: Path, new_perms: list[str], start_bit: int, new_version: int, dry_run: bool
) -> None:
    """Append new AUTHORITY_BY_BIT entries to GatewayPermissionCatalog.java and set CATALOG_VERSION."""
    java_path = root / GATEWAY_CATALOG_RELPATH
    text = java_path.read_text(encoding="utf-8")

    sorted_perms = sorted(new_perms)
    end_bit = start_bit + len(sorted_perms) - 1
    bar = "─" * 42
    new_lines = [f"\n        // ── New batch (bits {start_bit}–{end_bit}) {bar}"]
    for i, perm in enumerate(sorted_perms):
        bit = start_bit + i
        pad = " " * max(1, 45 - len(perm))
        comma = "," if i < len(sorted_perms) - 1 else ""
        new_lines.append(f'        "PERM_{perm}"{comma}{pad}// {bit}')

    new_block = "\n".join(new_lines)

    # The last array entry has no trailing comma. Match it and the closing };.
    # [^,\n]* matches the trailing spaces + optional // comment before newline.
    tail_re = re.compile(r'("PERM_[^"]+")([^,\n]*\n)(\s*\};)')
    m = tail_re.search(text)
    if not m:
        raise ValueError(
            "Cannot find last AUTHORITY_BY_BIT entry in GatewayPermissionCatalog.java"
        )

    new_text = (
        text[: m.start()]
        + m.group(1) + ","  # add comma to previous last entry
        + m.group(2)        # rest of that line (spaces + comment + newline)
        + new_block + "\n"
        + m.group(3)        # closing };
        + text[m.end() :]
    )

    new_text = re.sub(
        r"public static final int CATALOG_VERSION = \d+;",
        f"public static final int CATALOG_VERSION = {new_version};",
        new_text,
    )

    if not dry_run:
        java_path.write_text(new_text, encoding="utf-8")


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
    parser.add_argument(
        "--sync",
        action="store_true",
        help=(
            "Scan @PreAuthorize annotations, register any unknown permissions in "
            "PermissionCode.java and GatewayPermissionCatalog.java, and bump "
            "CATALOG_VERSION. Runs before permissions.yaml regeneration."
        ),
    )
    args = parser.parse_args()

    root = Path(args.root).resolve()

    catalog_error = False

    if args.sync:
        annotated = scan_all_preauthorize(root)
        registered, max_bit = parse_permission_code_java(root)
        new_perms = sorted(annotated - registered)

        if new_perms:
            prefix = "(dry-run) " if args.dry_run else ""
            if args.check:
                print(
                    f"\nERROR: {len(new_perms)} permission(s) in @PreAuthorize are not "
                    "registered in PermissionCode:",
                    file=sys.stderr,
                )
                for p in new_perms:
                    print(f"  - {p}", file=sys.stderr)
                print(
                    "Run scripts/generate-permissions.sh --sync to register them.",
                    file=sys.stderr,
                )
                catalog_error = True
            else:
                next_bit = max_bit + 1
                new_version = sync_permission_code_java(root, new_perms, next_bit, args.dry_run)
                sync_gateway_catalog_java(root, new_perms, next_bit, new_version, args.dry_run)
                print(f"{prefix}Catalog sync — {len(new_perms)} new permission(s) registered:")
                for i, p in enumerate(new_perms):
                    print(f"  + {p} (bit {next_bit + i})")
                print(f"  CATALOG_VERSION: {new_version - 1} → {new_version}")
        else:
            print("Catalog sync: up-to-date")

    if args.modules:
        module_paths = [root / m for m in args.modules]
    else:
        module_paths = discover_modules(root)

    any_yaml_changed = False
    for module_path in module_paths:
        result = process_module(module_path, args.dry_run, args.check)
        if result is None:
            continue

        module_name = result["module"]
        if result["changed"]:
            any_yaml_changed = True
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

    if args.check and any_yaml_changed:
        print(
            "\nERROR: One or more permissions.yaml files are out of date.",
            file=sys.stderr,
        )
        print(
            "Run scripts/generate-permissions.sh to regenerate them.",
            file=sys.stderr,
        )
        catalog_error = True

    if catalog_error:
        sys.exit(1)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
Regenerates src/main/resources/permissions.yaml for each module by scanning
@PreAuthorize annotations in Java source for hasAuthority / hasAnyAuthority calls.

Only permissions belonging to the module's own domain are written to its YAML.
Cross-domain references (e.g. pos-workorder using inventory:pick_list:view) are
logged as informational warnings but are not added to the file.

With --sync, also updates PermissionCode.java, GatewayPermissionCatalog.java, and
DownstreamPermissionCatalog.java by appending any @PreAuthorize permissions not yet
registered as bit-indexed enum constants, and bumps CATALOG_VERSION in all three files.

Usage:
    python3 scripts/generate-permissions.py ROOT_DIR [module ...] [--dry-run] [--check] [--sync]
"""

import argparse
import json
import re
import sys
from pathlib import Path

# Matches single-quoted permission strings: 'a:b' or 'a:b:c'
# Segments start with a letter and may contain mixed-case letters,
# digits, underscores, and hyphens — e.g. 'bulkImport:upload:execute'.
PERM_RE = re.compile(
    r"'([A-Za-z][A-Za-z0-9_-]*:[A-Za-z][A-Za-z0-9_-]*(?::[A-Za-z][A-Za-z0-9_-]*)?)'"
)

# The same permission shape as PERM_RE, but as the value of a Java string constant
# rather than a SpEL literal — e.g. public static final String X = "supplier:stock:inquire";
PERM_CONST_DECL_RE = re.compile(
    r"static\s+final\s+String\s+([A-Z][A-Z0-9_]*)\s*=\s*"
    r'"([A-Za-z][A-Za-z0-9_-]*:[A-Za-z][A-Za-z0-9_-]*(?::[A-Za-z][A-Za-z0-9_-]*)?)"'
)

# A constant reference inside @PreAuthorize: either qualified (SupplierPermissions.STOCK_INQUIRE)
# or bare (STOCK_INQUIRE, when the holder is statically imported or the same class).
CONST_REF_RE = re.compile(r"\b(?:([A-Z][A-Za-z0-9_]*)\.)?([A-Z][A-Z0-9_]{2,})\b")
ENUM_ENTRY_RE = re.compile(r'\((\d+),\s*"([^"]+)"\)')
CATALOG_ENTRY_RE = re.compile(r'"PERM_([^"]+)"')
CATALOG_VERSION_RE = re.compile(r"public static final int CATALOG_VERSION = (\d+);")
DRY_RUN_PREFIX = "(dry-run) "

PERMISSION_CODE_RELPATH = (
    "pos-security-service/src/main/java/com/positivity/securityservice"
    "/internal/enums/PermissionCode.java"
)
GATEWAY_CATALOG_RELPATH = (
    "pos-api-gateway/src/main/java/com/positivity/gateway/config"
    "/GatewayPermissionCatalog.java"
)
DOWNSTREAM_CATALOG_RELPATH = (
    "pos-security-common/src/main/java/com/positivity/security/common"
    "/DownstreamPermissionCatalog.java"
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


def build_permission_constant_map(root: Path) -> dict[str, str]:
    """
    Map every permission-valued Java string constant to its literal.

    Keyed twice: qualified ("SupplierPermissions.STOCK_INQUIRE") and bare
    ("STOCK_INQUIRE"). Modules write @PreAuthorize either way, and the qualified form
    is what disambiguates when two holders happen to share a constant name.

    Discovery is by declaration shape, not by file name. The repo uses at least two
    conventions — *Permissions.java and *PermissionRegistry.java — and a filename
    allowlist would silently miss the next one somebody invents.
    """
    qualified: dict[str, str] = {}
    bare: dict[str, set[str]] = {}
    for java_root in sorted(root.glob("pos-*/src/main/java")):
        for java_file in java_root.rglob("*.java"):
            text = java_file.read_text(encoding="utf-8", errors="replace")
            if "static final String" not in text:
                continue
            class_name = java_file.stem
            for m in PERM_CONST_DECL_RE.finditer(text):
                const_name, perm = m.group(1), m.group(2)
                qualified[f"{class_name}.{const_name}"] = perm
                bare.setdefault(const_name, set()).add(perm)

    # A bare name is only usable when it means one thing repo-wide. Two holders
    # disagreeing about STATUS_READ must not silently resolve to whichever was scanned
    # last — that would register a permission the annotation never referenced.
    for const_name, values in bare.items():
        if len(values) == 1:
            qualified.setdefault(const_name, next(iter(values)))
    return qualified


def resolve_constants(block: str, const_map: dict[str, str]) -> str:
    """
    Rewrite constant references in an @PreAuthorize block into SpEL literals.

    Emits 'value' so the existing PERM_RE picks them up unchanged — the scanner keeps
    one way of recognising a permission, and this only widens what reaches it.
    """
    if not const_map:
        return block

    def repl(m: re.Match) -> str:
        holder, const_name = m.group(1), m.group(2)
        if holder:
            perm = const_map.get(f"{holder}.{const_name}")
            if perm is None:
                # Qualified but unknown: do NOT fall back to the bare name. The holder
                # was named for a reason, and guessing past it is how a permission gets
                # attributed to the wrong constant.
                return m.group(0)
        else:
            perm = const_map.get(const_name)
        return f"'{perm}'" if perm else m.group(0)

    return CONST_REF_RE.sub(repl, block)


def normalize_domain_key(value: str) -> str:
    """Normalize ownership keys for tolerant domain/prefix matching."""
    return re.sub(r"[^a-z0-9]", "", value.lower())


def permission_belongs_to_domain(permission: str, domain: str) -> bool:
    """Match ownership even when domain keys differ by case/word separators."""
    if not domain or ":" not in permission:
        return False
    prefix = permission.split(":", 1)[0]
    return normalize_domain_key(prefix) == normalize_domain_key(domain)


def scan_module(
    module_path: Path, domain: str, const_map: dict[str, str] | None = None
) -> tuple[set[str], set[str]]:
    """
    Scan all .java files under <module>/src/main/java.
    Returns (own_perms, cross_domain_perms).
    """
    java_root = module_path / "src" / "main" / "java"
    own: set[str] = set()
    cross: set[str] = set()
    if not java_root.exists():
        return own, cross
    const_map = const_map or {}
    for java_file in java_root.rglob("*.java"):
        text = java_file.read_text(encoding="utf-8", errors="replace")
        for block in extract_preauthorize_blocks(text):
            block = resolve_constants(block, const_map)
            for m in PERM_RE.finditer(block):
                perm = m.group(1)
                if perm.startswith("ROLE_"):
                    continue
                if permission_belongs_to_domain(perm, domain):
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
    def yaml_double_quoted(value: str) -> str:
        # YAML accepts JSON-style quoted scalars; json.dumps safely escapes
        # control characters such as newlines and tabs.
        return json.dumps(value, ensure_ascii=True)

    lines = [
        f"domain: {domain}",
        f"serviceName: {service_name}",
        f"version: {yaml_double_quoted(version)}",
        "permissions:",
    ]
    for p in permissions:
        desc = p.get("description", "") or ""
        lines.append(f"  - name: {yaml_double_quoted(p['name'])}")
        lines.append(f"    description: {yaml_double_quoted(desc)}")
        # Optional deprecation metadata: only emitted when present, so untouched
        # manifests stay byte-identical. Stable field order: name, description,
        # deprecated, supersededBy.
        if p.get("deprecated"):
            lines.append("    deprecated: true")
        superseded_by = p.get("supersededBy")
        if superseded_by:
            lines.append(f"    supersededBy: {yaml_double_quoted(superseded_by)}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def process_module(
    module_path: Path, dry_run: bool, check: bool, const_map: dict[str, str] | None = None
) -> dict | None:
    yaml_path = module_path / "src" / "main" / "resources" / "permissions.yaml"
    if not yaml_path.exists():
        return None

    existing = load_existing_yaml(yaml_path)
    domain: str = existing.get("domain", "")
    service_name: str = existing.get("serviceName", module_path.name)
    version: str = str(existing.get("version", "1.0"))

    # Full existing entry metadata (description + optional deprecated/supersededBy),
    # keyed by name, so a later merge can't silently drop deprecation info that was
    # hand-added or carried from a previous sync.
    existing_entries: dict[str, dict] = {
        e["name"]: e
        for e in existing.get("permissions", [])
        if isinstance(e, dict) and "name" in e
    }

    own_perms, cross_perms = scan_module(module_path, domain, const_map)

    added = own_perms - existing_entries.keys()
    # Additive-only: never remove existing entries. Permissions may be enforced
    # via programmatic authority checks (e.g. authorities.contains()) that are
    # invisible to static @PreAuthorize scanning.
    merged_entries = {
        **{name: {"name": name, "description": default_description(name)} for name in own_perms},
        **existing_entries,
    }
    changed = bool(added)

    new_permissions = sorted(
        [
            {
                "name": name,
                "description": entry.get("description", "") or "",
                "deprecated": bool(entry.get("deprecated", False)),
                "supersededBy": entry.get("supersededBy"),
            }
            for name, entry in merged_entries.items()
        ],
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
# Catalog sync: keep PermissionCode.java, GatewayPermissionCatalog.java, and
# DownstreamPermissionCatalog.java in step with @PreAuthorize annotations.
# ──────────────────────────────────────────────────────────────────────────────

def scan_all_preauthorize(root: Path, const_map: dict[str, str] | None = None) -> set[str]:
    """Collect every permission string from @PreAuthorize across all pos-* modules."""
    all_perms: set[str] = set()
    const_map = const_map if const_map is not None else build_permission_constant_map(root)
    for java_root in sorted(root.glob("pos-*/src/main/java")):
        for java_file in java_root.rglob("*.java"):
            text = java_file.read_text(encoding="utf-8", errors="replace")
            for block in extract_preauthorize_blocks(text):
                block = resolve_constants(block, const_map)
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


def parse_permission_code_catalog(root: Path) -> tuple[list[str], int]:
    """Return the authoritative ordered permissions and catalog version."""
    text = (root / PERMISSION_CODE_RELPATH).read_text(encoding="utf-8")
    indexed_permissions = sorted(
        (int(bit), permission) for bit, permission in ENUM_ENTRY_RE.findall(text)
    )
    actual_bits = [bit for bit, _ in indexed_permissions]
    expected_bits = list(range(len(indexed_permissions)))
    if actual_bits != expected_bits:
        raise ValueError("PermissionCode bit indices must be contiguous from zero")

    version_match = CATALOG_VERSION_RE.search(text)
    if not version_match:
        raise ValueError("CATALOG_VERSION constant not found in PermissionCode.java")
    return [permission for _, permission in indexed_permissions], int(version_match.group(1))


def parse_mirror_catalog_java(root: Path, relative_path: str) -> tuple[list[str], int]:
    """Return ordered permissions and version from a gateway or downstream mirror."""
    text = (root / relative_path).read_text(encoding="utf-8")
    version_match = CATALOG_VERSION_RE.search(text)
    if not version_match:
        raise ValueError(f"CATALOG_VERSION constant not found in {Path(relative_path).name}")
    return CATALOG_ENTRY_RE.findall(text), int(version_match.group(1))


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
    """Append new AUTHORITY_BY_BIT entries to GatewayPermissionCatalog.java and set CATALOG_VERSION.

    new_perms must already be in bit order (position i is bit start_bit + i).
    reconcile_mirror_catalog_java's caller passes an ordered slice of
    PermissionCode.java's actual (bit, code) pairs; the sync_permission_code_java
    caller passes an already alphabetically-sorted list. Re-sorting here would
    silently reassign entries to the wrong bit whenever PermissionCode.java's
    batch is not itself in alphabetical order (e.g. a batch bit-ordered by
    controller/feature rather than by name) - PermissionCode.java and this
    mirror would then decode the same bit to two different permissions.
    """
    java_path = root / GATEWAY_CATALOG_RELPATH
    text = java_path.read_text(encoding="utf-8")

    sorted_perms = list(new_perms)
    end_bit = start_bit + len(sorted_perms) - 1
    bar = "─" * 42
    new_lines = [f"\n        // ── New batch (bits {start_bit}–{end_bit}) {bar}"]
    for i, perm in enumerate(sorted_perms):
        bit = start_bit + i
        comma = "," if i < len(sorted_perms) - 1 else ""
        new_lines.append(f'        "PERM_{perm}"{comma} // {bit}')

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


def sync_downstream_catalog_java(
    root: Path, new_perms: list[str], start_bit: int, new_version: int, dry_run: bool
) -> None:
    """Append new AUTHORITY_BY_BIT entries to DownstreamPermissionCatalog.java and set CATALOG_VERSION.

    See sync_gateway_catalog_java: new_perms must already be in bit order, not
    re-sorted here.
    """
    java_path = root / DOWNSTREAM_CATALOG_RELPATH
    text = java_path.read_text(encoding="utf-8")

    sorted_perms = list(new_perms)
    end_bit = start_bit + len(sorted_perms) - 1
    bar = "─" * 42
    new_lines = [f"\n        // ── New batch (bits {start_bit}–{end_bit}) {bar}"]
    for i, perm in enumerate(sorted_perms):
        bit = start_bit + i
        comma = "," if i < len(sorted_perms) - 1 else ""
        new_lines.append(f'        "PERM_{perm}"{comma} // {bit}')

    new_block = "\n".join(new_lines)

    tail_re = re.compile(r'("PERM_[^"]+")([^,\n]*\n)(\s*\};)')
    m = tail_re.search(text)
    if not m:
        raise ValueError(
            "Cannot find last AUTHORITY_BY_BIT entry in DownstreamPermissionCatalog.java"
        )

    new_text = (
        text[: m.start()]
        + m.group(1) + ","
        + m.group(2)
        + new_block + "\n"
        + m.group(3)
        + text[m.end() :]
    )

    new_text = re.sub(
        r"public static final int CATALOG_VERSION = \d+;",
        f"public static final int CATALOG_VERSION = {new_version};",
        new_text,
    )

    if not dry_run:
        java_path.write_text(new_text, encoding="utf-8")


def reconcile_mirror_catalog_java(
    root: Path,
    relative_path: str,
    expected_permissions: list[str],
    expected_version: int,
    dry_run: bool,
    check: bool,
) -> bool:
    """Repair suffix/version drift in a mirror and return whether drift was found."""
    actual_permissions, actual_version = parse_mirror_catalog_java(root, relative_path)
    if actual_permissions == expected_permissions and actual_version == expected_version:
        return False

    catalog_name = Path(relative_path).name
    if check:
        print(
            f"ERROR: {catalog_name} is out of sync with PermissionCode.java "
            f"(entries {len(actual_permissions)}/{len(expected_permissions)}, "
            f"version {actual_version}/{expected_version})",
            file=sys.stderr,
        )
        return True

    if actual_permissions != expected_permissions[: len(actual_permissions)]:
        raise ValueError(
            f"{catalog_name} is not an ordered prefix of PermissionCode.java; "
            "refusing to reassign permission bits"
        )

    missing_permissions = expected_permissions[len(actual_permissions) :]
    if missing_permissions:
        sync_function = (
            sync_gateway_catalog_java
            if relative_path == GATEWAY_CATALOG_RELPATH
            else sync_downstream_catalog_java
        )
        sync_function(
            root,
            missing_permissions,
            len(actual_permissions),
            expected_version,
            dry_run,
        )
    elif actual_version != expected_version:
        java_path = root / relative_path
        text = java_path.read_text(encoding="utf-8")
        new_text = CATALOG_VERSION_RE.sub(
            f"public static final int CATALOG_VERSION = {expected_version};", text
        )
        if not dry_run:
            java_path.write_text(new_text, encoding="utf-8")

    prefix = DRY_RUN_PREFIX if dry_run else ""
    print(f"{prefix}{catalog_name}: repaired")
    return True


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
        help=(
            "Exit non-zero if any permissions.yaml would change or, with --sync, "
            "if permission catalogs differ (CI mode)"
        ),
    )
    parser.add_argument(
        "--sync",
        action="store_true",
        help=(
            "Scan @PreAuthorize annotations, register any unknown permissions in "
            "PermissionCode.java, reconcile GatewayPermissionCatalog.java and "
            "DownstreamPermissionCatalog.java, and bump CATALOG_VERSION. Runs before "
            "permissions.yaml regeneration."
        ),
    )
    args = parser.parse_args()

    root = Path(args.root).resolve()

    # Built once and shared: resolving constant-based @PreAuthorize means reading every
    # module's permission constants, and doing that per module would re-read the repo
    # once for each of them.
    const_map = build_permission_constant_map(root)

    catalog_error = False

    if args.sync:
        expected_permissions, expected_version = parse_permission_code_catalog(root)
        mirror_drift = False
        for relative_path in (GATEWAY_CATALOG_RELPATH, DOWNSTREAM_CATALOG_RELPATH):
            drift = reconcile_mirror_catalog_java(
                root,
                relative_path,
                expected_permissions,
                expected_version,
                args.dry_run,
                args.check,
            )
            mirror_drift = mirror_drift or drift
            if args.check and drift:
                catalog_error = True

        annotated = scan_all_preauthorize(root, const_map)
        registered, max_bit = parse_permission_code_java(root)
        new_perms = sorted(annotated - registered)

        if new_perms:
            prefix = DRY_RUN_PREFIX if args.dry_run else ""
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
                sync_downstream_catalog_java(root, new_perms, next_bit, new_version, args.dry_run)
                print(f"{prefix}Catalog sync — {len(new_perms)} new permission(s) registered:")
                for i, p in enumerate(new_perms):
                    print(f"  + {p} (bit {next_bit + i})")
                print(f"  CATALOG_VERSION: {new_version - 1} → {new_version}")
        elif not mirror_drift:
            print("Catalog sync: up-to-date")

    if args.modules:
        module_paths = [root / m for m in args.modules]
    else:
        module_paths = discover_modules(root)

    any_yaml_changed = False
    for module_path in module_paths:
        result = process_module(module_path, args.dry_run, args.check, const_map)
        if result is None:
            continue

        module_name = result["module"]
        if result["changed"]:
            any_yaml_changed = True
            prefix = DRY_RUN_PREFIX if args.dry_run else ""
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

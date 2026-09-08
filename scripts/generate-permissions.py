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

--sync then grants those permissions in both grant sources (#1848) —
R__seed_role_permissions.sql (permission row, role grant, self-check entry) and
scripts/fixtures/seed/alpha/security/role-permissions.csv — because a bit without a grant
leaves every endpoint behind the permission unreachable. --sync --check reports that state
instead of fixing it.

Usage:
    python3 scripts/generate-permissions.py ROOT_DIR [module ...] \
        [--dry-run] [--check] [--sync] [--grant ROLE ...]
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

# ── Grant sources (#1848) ────────────────────────────────────────────────────
# A bit in PermissionCode makes a permission *expressible*; it does not make it
# reachable. Reachability comes from one of two grant sources, and a new code
# missing from both fails CI twice over (audit-rbac.py's required_ungranted /
# unreachable_op_count, and RoleBaselineDriftTest). Both are written here so the
# command that assigns the bit also lands the grant.
SEED_SQL_RELPATH = (
    "pos-security-service/src/main/resources/db/migration/R__seed_role_permissions.sql"
)
ROLE_PERMISSIONS_CSV_RELPATH = "scripts/fixtures/seed/alpha/security/role-permissions.csv"

# The only roles the repeatable seed may grant to (#1613 D8): the
# ADMIN / SYSTEM_ADMINISTRATOR bootstrap floor plus the four checksum-frozen roles
# still created by versioned migrations. RoleBaselineDriftTest#seedGrantsOnlyToRolesItCreates
# fails the build on anything else, and the seed's own section-4 guard raises on a role
# it cannot resolve. Every other role's grants live in the alpha baseline CSV only.
SEED_GRANT_ROLES = frozenset({
    "ADMIN",
    "CONTROLLER",
    "DISPATCHER",
    "SELF_SERVICE_CUSTOMER",
    "SHOP_MANAGER",
    "SYSTEM_ADMINISTRATOR",
})

# ADMIN is the all-domain role and a strict superset of every other role, so a new
# permission granted nowhere else is still reachable by an administrator. It is the
# safe default precisely because it widens nothing an operator role can reach.
DEFAULT_GRANT_ROLE = "ADMIN"

# SYSTEM_ADMINISTRATOR is deliberately not a superuser, and its seed block is duplicated
# in V31__revoke_system_administrator_out_of_band_grants.sql (SQL cannot read a repeatable
# migration in another file). RolePermissionBaselineTest fails the build when the two copies
# disagree, and this script does not edit V31 — so widening this role stays a hand edit of
# both files rather than a flag that lands half of it.
UNGRANTABLE_ROLES = frozenset({"SYSTEM_ADMINISTRATOR"})

# End markers of the three VALUES blocks this script edits inside the seed. Anchoring
# on the trailing alias rather than on line numbers keeps the edit stable as the file
# grows by hundreds of rows.
SEED_PERMISSION_ROWS_MARKER = ") AS c(name, domain, resource, action, bit_index)"
SEED_GRANT_ROWS_MARKER = ") AS g(role_name, permission_name)"
SEED_SELF_CHECK_MARKER = ") AS g(permission_name)"
VALUES_BLOCK_START = "FROM (VALUES\n"

SEED_PERMISSION_ROW_RE = re.compile(
    r"^\s*\('([^']+)',\s*'([^']*)',\s*'([^']*)',\s*'([^']*)',\s*\d+\),?$"
)
SEED_GRANT_ROW_RE = re.compile(r"^\s*\('([A-Z][A-Z0-9_]*)',\s*'([^']+)'\),?$")
SEED_SELF_CHECK_ROW_RE = re.compile(r"^\s*\('([^']+)'\),?$")


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
        # Optional metadata: only emitted when present, so untouched manifests stay
        # byte-identical. Stable field order: name, description, deprecated,
        # supersededBy, grantTo.
        if p.get("deprecated"):
            lines.append("    deprecated: true")
        superseded_by = p.get("supersededBy")
        if superseded_by:
            lines.append(f"    supersededBy: {yaml_double_quoted(superseded_by)}")
        # grantTo (#1848) names the roles --sync should grant this permission to,
        # overriding --grant / the ADMIN default. Hand-written; preserved verbatim so a
        # regeneration cannot silently drop the decision it records.
        grant_to = p.get("grantTo")
        if grant_to:
            lines.append("    grantTo:")
            for role in grant_to:
                lines.append(f"      - {yaml_double_quoted(role)}")
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
                "grantTo": entry.get("grantTo"),
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


# ──────────────────────────────────────────────────────────────────────────────
# Grant sync (#1848): keep R__seed_role_permissions.sql and the alpha role
# baseline CSV in step with the catalog, so a new bit arrives already reachable.
# ──────────────────────────────────────────────────────────────────────────────

def split_permission(perm: str) -> tuple[str, str, str]:
    """
    'domain:resource:action' → ('domain', 'resource', 'action').

    Two-segment codes ('appointments:cancel') have no resource; the seed stores an
    empty string for those, matching the rows already in the file.
    """
    parts = perm.split(":")
    if len(parts) == 3:
        return parts[0], parts[1], parts[2]
    if len(parts) == 2:
        return parts[0], "", parts[1]
    raise ValueError(f"Cannot split permission into domain/resource/action: {perm!r}")


def _values_block_bounds(text: str, end_marker: str) -> tuple[int, int]:
    """Character span of the VALUES rows that end at end_marker."""
    end = text.find(end_marker)
    if end == -1:
        raise ValueError(f"Cannot find {end_marker!r} in the seed migration")
    start = text.rfind(VALUES_BLOCK_START, 0, end)
    if start == -1:
        raise ValueError(f"Cannot find the VALUES list preceding {end_marker!r}")
    return start + len(VALUES_BLOCK_START), end


def insert_rows_sorted(block: str, additions: list[tuple[object, str]], key_of_line) -> str:
    """
    Insert rendered rows into a VALUES block at their sorted position.

    Existing rows are never reordered — the seed's ordering has a handful of historical
    deviations from a strict sort (accounting:gl:reconcile before accounting:gl-mapping:*),
    and re-sorting the file to "fix" them would bury a one-line change in a 500-line diff.
    Each new row goes before the first row that sorts after it, which is the same position
    a fresh sort would give it, and appended rows pick up the comma the previous last row
    was missing.
    """
    lines = block.split("\n")
    for key, rendered in sorted(additions, key=lambda item: item[0]):
        index = None
        for i, line in enumerate(lines):
            line_key = key_of_line(line)
            if line_key is not None and line_key > key:
                index = i
                break
        if index is None:
            entry_indexes = [i for i, line in enumerate(lines) if key_of_line(line) is not None]
            if not entry_indexes:
                raise ValueError("VALUES block has no rows to anchor an append against")
            index = entry_indexes[-1] + 1
        lines.insert(index, rendered)

    entry_indexes = [i for i, line in enumerate(lines) if key_of_line(line) is not None]
    for position, i in enumerate(entry_indexes):
        row = lines[i].rstrip()
        if row.endswith(","):
            row = row[:-1]
        lines[i] = row + ("," if position < len(entry_indexes) - 1 else "")
    return "\n".join(lines)


def _permission_row_key(line: str):
    m = SEED_PERMISSION_ROW_RE.match(line)
    return m.group(1) if m else None


def _grant_row_key(line: str):
    m = SEED_GRANT_ROW_RE.match(line)
    return (m.group(1), m.group(2)) if m else None


def _self_check_row_key(line: str):
    m = SEED_SELF_CHECK_ROW_RE.match(line)
    return m.group(1) if m else None


def grant_sources_present(root: Path) -> bool:
    """Whether this root carries both grant sources.

    Partial roots are real: the sync tests build a tree holding only the catalog files, and
    generate-openapi.sh can be pointed at one. A missing grant source means there is nothing
    to reconcile, not that the run should fail.
    """
    return (root / SEED_SQL_RELPATH).exists() and (root / ROLE_PERMISSIONS_CSV_RELPATH).exists()


def parse_seed_grants(root: Path) -> dict[str, set[str]]:
    """role → permissions granted by the repeatable seed."""
    seed_path = root / SEED_SQL_RELPATH
    if not seed_path.exists():
        return {}
    text = seed_path.read_text(encoding="utf-8")
    start, end = _values_block_bounds(text, SEED_GRANT_ROWS_MARKER)
    grants: dict[str, set[str]] = {}
    for line in text[start:end].split("\n"):
        key = _grant_row_key(line)
        if key:
            grants.setdefault(key[0], set()).add(key[1])
    return grants


def parse_baseline_grants(root: Path) -> dict[str, list[str]]:
    """role → permissions granted by the alpha role baseline CSV, in file order."""
    csv_path = root / ROLE_PERMISSIONS_CSV_RELPATH
    grants: dict[str, list[str]] = {}
    if not csv_path.exists():
        return grants
    for line in csv_path.read_text(encoding="utf-8").splitlines()[1:]:
        if not line.strip():
            continue
        role, _, permissions = line.partition(",")
        grants[role] = [p for p in permissions.strip().strip('"').split(";") if p]
    return grants


def all_granted_permissions(root: Path) -> set[str]:
    """Every permission reachable from either grant source."""
    granted: set[str] = set()
    for permissions in parse_seed_grants(root).values():
        granted |= permissions
    for permissions in parse_baseline_grants(root).values():
        granted |= set(permissions)
    return granted


def resolve_grant_roles(
    permission: str, manifest_grants: dict[str, list[str]], cli_roles: list[str]
) -> list[str]:
    """
    Roles a new permission should be granted to.

    The owning module's permissions.yaml wins when it names a `grantTo` list for the
    permission — that decision is per-permission and reviewed in the module that owns it.
    Otherwise the run-wide --grant values apply, and ADMIN is the fallback.
    """
    declared = manifest_grants.get(permission)
    if declared:
        roles = sorted(set(declared))
    elif cli_roles:
        roles = sorted(set(cli_roles))
    else:
        roles = [DEFAULT_GRANT_ROLE]
    refused = sorted(set(roles) & UNGRANTABLE_ROLES)
    if refused:
        raise ValueError(
            f"Refusing to grant {permission} to {', '.join(refused)}: that role's seed block is "
            "mirrored in V31__revoke_system_administrator_out_of_band_grants.sql, which this "
            "script does not edit. Make the grant by hand in both files."
        )
    return roles


def collect_manifest_grant_targets(root: Path) -> dict[str, list[str]]:
    """permission → grantTo roles declared in any module's permissions.yaml."""
    targets: dict[str, list[str]] = {}
    for yaml_path in sorted(root.glob("pos-*/src/main/resources/permissions.yaml")):
        for entry in load_existing_yaml(yaml_path).get("permissions", []) or []:
            if not isinstance(entry, dict) or "name" not in entry:
                continue
            grant_to = entry.get("grantTo")
            if isinstance(grant_to, str):
                grant_to = [grant_to]
            if grant_to:
                targets[entry["name"]] = [str(role) for role in grant_to]
    return targets


def sync_seed_sql(
    root: Path, grants: dict[str, list[str]], bit_by_permission: dict[str, int], dry_run: bool
) -> list[str]:
    """
    Add permission rows, role grants and self-check entries to the repeatable seed.

    `grants` is permission → roles. Only SEED_GRANT_ROLES reach the SQL grant block; the
    rest are the baseline CSV's job, and writing them here would fail both the drift test
    and the seed's own unresolved-role guard.

    The permission row (section 2) is written for every permission either way —
    role_permissions has a foreign key to permissions, and the CSV loader resolves its grants
    by name against the same table, so the row has to exist whichever source grants it. The
    self-check list (section 4) is written only for permissions this file actually grants:
    RolePermissionBaselineTest#resolutionAssertionMatchesTheGrants pins it equal to the
    section-3 grant set in both directions, so an entry for a CSV-only grant fails the build.
    """
    sql_path = root / SEED_SQL_RELPATH
    text = sql_path.read_text(encoding="utf-8")
    messages: list[str] = []

    # 1. permissions rows (section 2)
    start, end = _values_block_bounds(text, SEED_PERMISSION_ROWS_MARKER)
    block = text[start:end]
    existing_rows = {
        key for key in (_permission_row_key(line) for line in block.split("\n")) if key
    }
    additions = []
    for permission in sorted(grants):
        if permission in existing_rows:
            continue
        bit = bit_by_permission.get(permission)
        if bit is None:
            raise ValueError(f"No PermissionCode bit known for {permission!r}")
        domain, resource, action = split_permission(permission)
        additions.append(
            (
                permission,
                f"    ('{permission}', '{domain}', '{resource}', '{action}', {bit}),",
            )
        )
    if additions:
        text = text[:start] + insert_rows_sorted(block, additions, _permission_row_key) + text[end:]
        messages.append(f"seed permissions rows: +{len(additions)}")

    # 2. role grants (section 3)
    start, end = _values_block_bounds(text, SEED_GRANT_ROWS_MARKER)
    block = text[start:end]
    existing_grants = {
        key for key in (_grant_row_key(line) for line in block.split("\n")) if key
    }
    additions = []
    seed_granted: set[str] = set()
    for permission in sorted(grants):
        for role in sorted(grants[permission]):
            if role not in SEED_GRANT_ROLES:
                continue
            seed_granted.add(permission)
            if (role, permission) in existing_grants:
                continue
            additions.append(((role, permission), f"    ('{role}', '{permission}'),"))
    if additions:
        text = text[:start] + insert_rows_sorted(block, additions, _grant_row_key) + text[end:]
        messages.append(f"seed role grants: +{len(additions)}")

    # 3. self-check list (section 4)
    start, end = _values_block_bounds(text, SEED_SELF_CHECK_MARKER)
    block = text[start:end]
    existing_checks = {
        key for key in (_self_check_row_key(line) for line in block.split("\n")) if key
    }
    additions = [
        (permission, f"        ('{permission}'),")
        for permission in sorted(seed_granted)
        if permission not in existing_checks
    ]
    if additions:
        text = text[:start] + insert_rows_sorted(block, additions, _self_check_row_key) + text[end:]
        messages.append(f"seed self-check entries: +{len(additions)}")

    if messages and not dry_run:
        sql_path.write_text(text, encoding="utf-8")
    return messages


def sync_role_permissions_csv(
    root: Path, grants: dict[str, list[str]], dry_run: bool
) -> list[str]:
    """
    Add grants to the alpha role baseline CSV, keeping each row's `;` list sorted.

    Rows are edited, never created: the role set is pinned by RoleBaselineDriftTest, so a
    --grant naming a role that has no row is a typo, not a new role, and is refused rather
    than quietly inventing a baseline the loader would then provision.
    """
    csv_path = root / ROLE_PERMISSIONS_CSV_RELPATH
    lines = csv_path.read_text(encoding="utf-8").splitlines()
    by_role: dict[str, list[str]] = {}
    for permission, roles in grants.items():
        for role in roles:
            by_role.setdefault(role, []).append(permission)

    known_roles = set(parse_baseline_grants(root))
    unknown = sorted(set(by_role) - known_roles)
    if unknown:
        raise ValueError(
            f"{csv_path.name} has no row for role(s): {', '.join(unknown)}. "
            "Add the role to the baseline (and to RoleBaselineDriftTest) first."
        )

    added = 0
    for i, line in enumerate(lines[1:], start=1):
        if not line.strip():
            continue
        role, _, permissions = line.partition(",")
        wanted = by_role.get(role)
        if not wanted:
            continue
        current = [p for p in permissions.strip().split(";") if p]
        new = [p for p in wanted if p not in current]
        if not new:
            continue
        merged = sorted(set(current) | set(new))
        lines[i] = f"{role},{';'.join(merged)}"
        added += len(new)

    if not added:
        return []
    if not dry_run:
        csv_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return [f"alpha role baseline: +{added} grant(s)"]


def validate_grant_roles(root: Path, roles: list[str]) -> None:
    """
    Reject a --grant role before anything is written.

    The catalog sync writes PermissionCode.java and both mirrors before the grant sync runs,
    so a role name only checked at write time would leave a bit assigned and no grant — the
    exact half-applied state this feature exists to remove.
    """
    refused = sorted(set(roles) & UNGRANTABLE_ROLES)
    if refused:
        raise ValueError(
            f"Refusing to grant to {', '.join(refused)}: that role's seed block is mirrored in "
            "V31__revoke_system_administrator_out_of_band_grants.sql, which this script does not "
            "edit. Make the grant by hand in both files."
        )
    if not grant_sources_present(root):
        return
    unknown = sorted(set(roles) - set(parse_baseline_grants(root)))
    if unknown:
        raise ValueError(
            f"{Path(ROLE_PERMISSIONS_CSV_RELPATH).name} has no row for role(s): "
            f"{', '.join(unknown)}. Add the role to the baseline (and to "
            "RoleBaselineDriftTest) first."
        )


def sync_grant_sources(
    root: Path,
    permissions: list[str],
    bit_by_permission: dict[str, int],
    cli_roles: list[str],
    dry_run: bool,
) -> dict[str, list[str]]:
    """Grant `permissions` in both sources; returns permission → roles actually targeted."""
    manifest_grants = collect_manifest_grant_targets(root)
    grants = {
        permission: resolve_grant_roles(permission, manifest_grants, cli_roles)
        for permission in permissions
    }
    if not grants:
        return {}
    messages = sync_seed_sql(root, grants, bit_by_permission, dry_run)
    messages += sync_role_permissions_csv(root, grants, dry_run)
    prefix = DRY_RUN_PREFIX if dry_run else ""
    print(f"{prefix}Grant sync — {len(grants)} permission(s):")
    for permission in sorted(grants):
        print(f"  + {permission} → {', '.join(grants[permission])}")
    for message in messages:
        print(f"  {message}")
    return grants


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
            "permissions.yaml regeneration. Also grants every newly bit-indexed "
            "permission in both grant sources (see --grant)."
        ),
    )
    parser.add_argument(
        "--grant",
        action="append",
        default=[],
        metavar="ROLE",
        dest="grant",
        help=(
            "Role to grant newly registered permissions to, in R__seed_role_permissions.sql "
            "and scripts/fixtures/seed/alpha/security/role-permissions.csv. Repeatable. "
            f"Defaults to {DEFAULT_GRANT_ROLE}; a permission whose module manifest declares "
            "grantTo uses that instead. Only meaningful with --sync."
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
        try:
            validate_grant_roles(root, args.grant)
        except ValueError as exc:
            print(f"ERROR: {exc}", file=sys.stderr)
            sys.exit(1)

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
        next_bit = max_bit + 1
        bit_by_permission = {
            code: int(bit) for bit, code in ENUM_ENTRY_RE.findall(
                (root / PERMISSION_CODE_RELPATH).read_text(encoding="utf-8")
            )
        }
        bit_by_permission.update({p: next_bit + i for i, p in enumerate(new_perms)})

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
                new_version = sync_permission_code_java(root, new_perms, next_bit, args.dry_run)
                sync_gateway_catalog_java(root, new_perms, next_bit, new_version, args.dry_run)
                sync_downstream_catalog_java(root, new_perms, next_bit, new_version, args.dry_run)
                print(f"{prefix}Catalog sync — {len(new_perms)} new permission(s) registered:")
                for i, p in enumerate(new_perms):
                    print(f"  + {p} (bit {next_bit + i})")
                print(f"  CATALOG_VERSION: {new_version - 1} → {new_version}")
        elif not mirror_drift:
            print("Catalog sync: up-to-date")

        # Grant sync (#1848). A bit alone leaves the permission unreachable: the gateway can
        # encode it, but no role holds it, so every endpoint behind it 403s. Scoped to
        # permissions an annotation actually requires — a catalog code no @PreAuthorize names
        # is dead weight rather than a broken endpoint, and audit-rbac.py's catalog_dead is
        # where that is triaged.
        granted = all_granted_permissions(root)
        ungranted = (
            sorted((annotated & bit_by_permission.keys()) - granted)
            if grant_sources_present(root)
            else []
        )
        if not grant_sources_present(root):
            print("Grant sync: skipped (no grant sources under this root)")
        elif args.check:
            if ungranted:
                print(
                    f"\nERROR: {len(ungranted)} permission(s) have a PermissionCode bit and a "
                    "@PreAuthorize but are granted in neither R__seed_role_permissions.sql nor "
                    "the alpha role baseline — every endpoint behind them is unreachable:",
                    file=sys.stderr,
                )
                for p in ungranted:
                    print(f"  - {p}", file=sys.stderr)
                print(
                    "Run scripts/generate-permissions.sh --sync --grant <ROLE> to grant them.",
                    file=sys.stderr,
                )
                catalog_error = True
        elif ungranted:
            try:
                sync_grant_sources(root, ungranted, bit_by_permission, args.grant, args.dry_run)
            except ValueError as exc:
                print(f"ERROR: {exc}", file=sys.stderr)
                sys.exit(1)
        else:
            print("Grant sync: up-to-date")

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

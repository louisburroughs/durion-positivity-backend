#!/usr/bin/env python3
"""RBAC cross-reference audit (issues #1499 / #1512).

Cross-references four sources of truth about the authorization model:

  A. Granted   -- role -> permission rows in
                  pos-security-service/src/main/resources/db/migration/R__seed_role_permissions.sql
  B. Contract  -- x-required-permissions entries across all pos-*/openapi.yaml
  C. Code      -- permissions reachable from @PreAuthorize/@PostAuthorize
                  (constant references resolved), plus non-annotation
                  enforcement (SecurityContextHelper.hasAuthority(...),
                  authorities.contains(...), etc.)
  D. Registry  -- per-module src/main/resources/permissions.yaml manifests
  E. Catalog   -- PermissionCode enum (permanent JWT bit indexes)

and reports every disagreement between them:

  granted_unrequired    granted in the seed, required by no operation (#1499)
  required_ungranted    required by an operation, granted to no role (#1512)
  required_unregistered required but absent from every permissions.yaml
  registered_unrequired in a permissions.yaml but required nowhere
  required_no_bit       required but absent from PermissionCode -- unreachable:
                        JwtServiceImpl drops authorities with no bit index
  granted_no_bit        granted but absent from PermissionCode -- same trap
  catalog_dead          bit assigned, but neither granted nor required
  unreachable_ops       contract operations none of whose alternates is granted

Run from the repo root; no build, no database:

  python3 scripts/audit-rbac.py [output.json]

CI gate mode -- fail on NEW authorization drift, tolerate the documented
backlog (see docs/rbac-permission-role-audit-2026-08.md §7 task 7):

  python3 scripts/audit-rbac.py --check [--baseline PATH]

--check evaluates five defect classes against scripts/rbac-audit-baseline.json
(override with --baseline): required_ungranted, granted_unrequired,
required_no_bit, granted_no_bit, unreachable_op_count. Any code in those first
four flags that is not listed in the baseline is new drift and fails the
build; unreachable_op_count fails on any value > 0 (never baselined -- it
should always be zero). A baselined code that no longer drifts is also a
failure ("stale baseline") -- the baseline is meant to shrink, not just grow.
required_unregistered and catalog_dead are informational only and never gate.

Known limitations (see docs/rbac-permission-role-audit-2026-08.md):
  - x-required-permissions alternates are treated as OR (mirrors
    hasAnyAuthority); complex and() expressions are not modelled.
  - Dynamically constructed permission strings (e.g. "people:timeEntry:" +
    action) are only partially visible.
  - 149 of 999 contract operations carry no x-required-permissions at all,
    so "required nowhere" is an upper bound for those modules.
"""
import re, pathlib, collections, json, sys

# ---- argv -------------------------------------------------------------------
# Minimal manual parsing (no argparse elsewhere in this script): positional
# output path for normal mode, or --check [--baseline PATH] for the CI gate.
argv = sys.argv[1:]
check_mode = "--check" in argv
if check_mode:
    argv.remove("--check")
baseline_path = "scripts/rbac-audit-baseline.json"
if "--baseline" in argv:
    idx = argv.index("--baseline")
    baseline_path = argv[idx + 1]
    del argv[idx:idx + 2]
output_path = argv[0] if argv else None

root = pathlib.Path(".")

PERM_RE = r"[a-z][a-zA-Z0-9_.-]*:[a-zA-Z0-9_.:-]+"

# ---- comment stripping ------------------------------------------------------
def strip_comments(src):
    """Blank out // and /* */ comments, preserving every offset and newline.

    Enforcement is scanned by regex, and javadoc quotes annotations for
    illustration -- RoleAuthorityServiceImpl's class javadoc contains
    `@PreAuthorize("hasAuthority(\'crm:party:view\')")` as an EXAMPLE, and
    TaxServiceClient has `// @PreAuthorize(\'tax:calculate\')` describing another
    module's gate. Scoring prose as enforcement is the same false-positive class
    as the @EmitEvent-id bug (see the balanced-paren note below), so comments are
    blanked before scanning. Offsets are preserved (comment characters become
    spaces, newlines kept) so reported line numbers stay correct.
    """
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c == '"' or c == "'":  # string/char literal: copy verbatim
            q = c
            out.append(c)
            i += 1
            while i < n:
                out.append(src[i])
                if src[i] == "\\" and i + 1 < n:   # escape: copy the pair
                    i += 1
                    if i < n:
                        out.append(src[i])
                elif src[i] == q:
                    i += 1
                    break
                i += 1
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            while i < n and src[i] != "\n":
                out.append(" ")
                i += 1
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "*":
            while i < n and not (src[i] == "*" and i + 1 < n and src[i + 1] == "/"):
                out.append("\n" if src[i] == "\n" else " ")
                i += 1
            out.append("  ")          # the closing */
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


# ---- constants -> codes -----------------------------------------------------
const_to_code = {}
java_files = list(root.glob("pos-*/src/main/java/**/*.java"))
file_bodies = {f: strip_comments(f.read_text()) for f in java_files}
for f in java_files:
    for m in re.finditer(r'static final String (\w+)\s*=\s*"(' + PERM_RE + r')"', file_bodies[f]):
        const_to_code.setdefault(m.group(1), set()).add(m.group(2))

# Second pass: alias constants that reference another constant instead of a string
# literal directly (e.g. `PutawayPermissions.OVERRIDE_LOCATION_CAPACITY =
# InventoryPermissionRegistry.PUTAWAY_OVERRIDE_LOCATION_CAPACITY;`). One hop is
# enough here -- the referenced name is already fully resolved by the pass above.
for f in java_files:
    for m in re.finditer(r'static final String (\w+)\s*=\s*(?:[A-Za-z_]\w*\.)?(\w+)\s*;', file_bodies[f]):
        name, ref = m.group(1), m.group(2)
        if ref in const_to_code:
            const_to_code.setdefault(name, set()).update(const_to_code[ref])

# ---- C. enforced in code ----------------------------------------------------
enforced = collections.defaultdict(set)  # perm -> set of file:line
for f in java_files:
    body = file_bodies[f]
    for m in re.finditer(r'@(?:Pre|Post)Authorize\s*\(', body):
        # Read exactly the annotation's own argument expression, balanced-paren.
        # A fixed-size window instead swept in whatever followed -- @EmitEvent ids,
        # javadoc prose -- and any ALL-CAPS token there that happened to match a
        # permission-constant name elsewhere in the repo was scored as enforcement
        # (e.g. `@EmitEvent(id = "VEHICLE_SEARCH")` under a @PreAuthorize resolved
        # to pos-customer's VEHICLE_SEARCH = "crm:vehicle:search").
        depth, i, end = 1, m.end(), None
        while i < len(body) and i - m.end() < 2000:
            if body[i] == "(":
                depth += 1
            elif body[i] == ")":
                depth -= 1
                if depth == 0:
                    end = i
                    break
            i += 1
        chunk = body[m.end():end if end is not None else m.end()]
        line = body[:m.start()].count("\n") + 1
        for code in re.findall(r'"(' + PERM_RE + r')"', chunk):
            enforced[code].add(f"{f}:{line}")
        # SpEL string literals are SINGLE-quoted inside the Java string, so a code
        # written inline -- `hasAnyAuthority('workorder:parts:add', ...)` in
        # SubstituteLinkController, or the deliberate cross-module literal in
        # PurchaseSuggestionController -- is invisible to the double-quoted scan
        # above. Missing those reads as "enforced nowhere", the exact false
        # positive this audit exists to avoid.
        for code in re.findall(r"'(" + PERM_RE + r")'", chunk):
            enforced[code].add(f"{f}:{line}")
        for cst in re.findall(r'\b([A-Z][A-Z0-9_]{2,})\b', chunk):
            for code in const_to_code.get(cst, ()):
                enforced[code].add(f"{f}:{line}")
    # non-annotation enforcement: hasAuthority("..."), authorities.contains(...),
    # and any other permission-typed call -- .contains(...) plus any call whose
    # name contains "Permission" or "Authority" (hasAuthority, hasAnyAuthority,
    # hasPermission, requirePermission, checkPermission, and private helpers like
    # enforceOverridePermission(...) all match), including constant-reference
    # arguments.
    for m in re.finditer(
        r'(?:\.contains|\w*(?:Permission|Authority)\w*)'
        r'\(\s*([^)]{0,200})\)', body):
        arg = m.group(1)
        line = body[:m.start()].count("\n") + 1
        for code in re.findall(r'"(' + PERM_RE + r')"', arg):
            enforced[code].add(f"{f}:{line} (capability-flag)")
        for cst in re.findall(r'\b([A-Z][A-Z0-9_]{2,})\b', arg):
            for code in const_to_code.get(cst, ()):
                enforced[code].add(f"{f}:{line} (capability-flag const)")
    # constant-resolved authority comparison outside a recognized call, e.g.
    # `.noneMatch(a -> WIP_VIEW_ALL_LOCATIONS.equals(a.getAuthority()))`: a
    # permission constant compared against a GrantedAuthority's value on a line
    # that mentions getAuthority/getAuthorities.
    for lineno, line in enumerate(body.splitlines(), start=1):
        if "getAuthority" not in line and "getAuthorities" not in line:
            continue
        for code in re.findall(r'"(' + PERM_RE + r')"', line):
            enforced[code].add(f"{f}:{lineno} (authority-comparison)")
        for cst in re.findall(r'\b([A-Z][A-Z0-9_]{2,})\b', line):
            for code in const_to_code.get(cst, ()):
                enforced[code].add(f"{f}:{lineno} (authority-comparison const)")

# ---- B. contract ------------------------------------------------------------
contract = collections.defaultdict(set)  # perm -> modules
op_total = 0
op_missing = collections.Counter()
op_counts = collections.Counter()
for oapi in sorted(root.glob("pos-*/openapi.yaml")):
    mod = oapi.parent.name
    text = oapi.read_text()
    ops = re.findall(r'^ {4}(get|put|post|delete|patch):\s*$', text, re.M)
    op_counts[mod] = len(ops)
    op_total += len(ops)
    blocks = re.findall(r"x-required-permissions:\n((?:\s+- .+\n)+)", text)
    op_missing[mod] = len(ops) - len(re.findall(r"x-required-permissions:", text))
    for block in blocks:
        for line in block.strip().splitlines():
            entry = line.strip().lstrip("- ").strip()
            # AUTHENTICATED is the isAuthenticated-only sentinel emitted by
            # RequiredPermissionsOpenApiAutoConfiguration, not a permission code —
            # it can never be granted, registered, or bit-indexed, so keep it out
            # of the required set (op-level reachability special-cases it below).
            if entry == "AUTHENTICATED":
                continue
            contract[entry].add(mod)

# ---- A. granted -------------------------------------------------------------
seed_path = root / "pos-security-service/src/main/resources/db/migration/R__seed_role_permissions.sql"
seed = seed_path.read_text()
grants = collections.defaultdict(set)      # perm -> roles
role_perms = collections.defaultdict(set)  # role -> perms
for role, perm in re.findall(r"\(\s*'([A-Z][A-Z_]+)'\s*,\s*'(" + PERM_RE + r")'\s*\)", seed):
    grants[perm].add(role)
    role_perms[role].add(perm)

# ---- D. registry (per-module permissions.yaml manifests) --------------------
registry = collections.defaultdict(set)  # perm -> modules
for f in root.glob("pos-*/src/main/resources/permissions.yaml"):
    mod = f.parts[0]
    for m in re.finditer(r'-\s+name:\s*"?(' + PERM_RE + r')"?', f.read_text()):
        registry[m.group(1)].add(mod)

# ---- E. PermissionCode catalog (bit indexes) --------------------------------
pc_path = root / "pos-security-service/src/main/java/com/positivity/securityservice/internal/enums/PermissionCode.java"
catalog = {}  # code -> bit index
for m in re.finditer(r'(\w+)\((\d+),\s*"(' + PERM_RE + r')"\)', pc_path.read_text()):
    catalog[m.group(3)] = int(m.group(2))

# ---- op-level reachability --------------------------------------------------
# Listed perms are treated as OR-alternates (mirrors hasAnyAuthority). An op is
# unreachable when no listed perm is granted to any role and the AUTHENTICATED
# sentinel is not among them.
unreachable_ops = collections.defaultdict(list)  # module -> [(path, method, perms)]
for oapi in sorted(root.glob("pos-*/openapi.yaml")):
    mod = oapi.parent.name
    lines = oapi.read_text().splitlines()
    cur_path, cur_method = None, None
    i = 0
    while i < len(lines):
        ln = lines[i]
        pm = re.match(r'^  (/[^\s:]*):\s*$', ln)
        if pm:
            cur_path = pm.group(1)
        mm = re.match(r'^    (get|put|post|delete|patch):\s*$', ln)
        if mm:
            cur_method = mm.group(1)
        if re.match(r'^\s+x-required-permissions:\s*$', ln):
            perms = []
            j = i + 1
            while j < len(lines) and (m2 := re.match(r'^\s+-\s+(\S+)\s*$', lines[j])):
                perms.append(m2.group(1))
                j += 1
            if perms and "AUTHENTICATED" not in perms and not any(p in grants for p in perms):
                unreachable_ops[mod].append((cur_path, cur_method, perms))
            i = j
            continue
        i += 1

required = set(contract) | set(enforced)

flag_granted_unrequired = sorted(p for p in grants if p not in required)
flag_required_ungranted = sorted(p for p in required if p not in grants)
flag_required_unregistered = sorted(p for p in required if p not in registry)
flag_registered_unrequired = sorted(p for p in registry if p not in required)
flag_required_no_bit = sorted(p for p in required if p not in catalog)
flag_granted_no_bit = sorted(p for p in grants if p not in catalog)
flag_catalog_dead = sorted(p for p in catalog if p not in required and p not in grants)

out = {
    "counts": {
        "granted_distinct": len(grants),
        "contract_distinct": len(contract),
        "code_enforced_distinct": len(enforced),
        "required_union": len(required),
        "registry_distinct": len(registry),
        "catalog_bits": len(catalog),
        "roles": len(role_perms),
        "operations_total": op_total,
        "operations_missing_xrp": sum(op_missing.values()),
        "granted_unrequired": len(flag_granted_unrequired),
        "required_ungranted": len(flag_required_ungranted),
        "required_unregistered": len(flag_required_unregistered),
        "registered_unrequired": len(flag_registered_unrequired),
        "required_no_bit": len(flag_required_no_bit),
        "granted_no_bit": len(flag_granted_no_bit),
        "catalog_dead": len(flag_catalog_dead),
        "unreachable_op_count": sum(len(v) for v in unreachable_ops.values()),
    },
    "roles": {r: len(ps) for r, ps in sorted(role_perms.items())},
    "op_missing_by_module": {m: [op_missing[m], op_counts[m]] for m in sorted(op_counts) if op_missing[m] > 0},
    "granted_unrequired": {p: sorted(grants[p]) for p in flag_granted_unrequired},
    "required_ungranted": {
        p: {
            "contract_modules": sorted(contract.get(p, [])),
            "enforced_at": sorted(enforced.get(p, []))[:3],
            "registered": p in registry,
            "bit": catalog.get(p),
        } for p in flag_required_ungranted
    },
    "required_unregistered": flag_required_unregistered,
    "registered_unrequired": {p: sorted(registry[p]) for p in flag_registered_unrequired},
    "required_no_bit": flag_required_no_bit,
    "granted_no_bit": flag_granted_no_bit,
    "catalog_dead": {p: catalog[p] for p in flag_catalog_dead},
    "unreachable_ops": {m: v for m, v in sorted(unreachable_ops.items())},
}

if not check_mode:
    json.dump(out, open(output_path, "w") if output_path else sys.stdout, indent=1)
    sys.exit(0)

# ---- --check: CI gate --------------------------------------------------------
# Fails on NEW drift in the four bit/wiring defect classes below (each is a
# distinct #1494/#1499/#1512-style bug), and on a STALE baseline entry (a
# baselined code that no longer drifts -- the baseline must shrink, not just
# accumulate). unreachable_op_count is never baselined: any value > 0 fails.
# required_unregistered / catalog_dead are informational only, see module
# docstring.
baseline_file = pathlib.Path(baseline_path)
if not baseline_file.exists():
    print(f"GATE ERROR: baseline file not found: {baseline_path}", file=sys.stderr)
    sys.exit(1)
baseline = json.loads(baseline_file.read_text())

gated = {
    "required_ungranted": flag_required_ungranted,
    "granted_unrequired": flag_granted_unrequired,
    "required_no_bit": flag_required_no_bit,
    "granted_no_bit": flag_granted_no_bit,
}
failed = False

for category, current in gated.items():
    current_set = set(current)
    baselined = baseline.get(category, {})
    new_drift = sorted(c for c in current_set if c not in baselined)
    stale = sorted(c for c in baselined if c not in current_set)

    if new_drift:
        failed = True
        print(f"\nNEW DRIFT -- {category} ({len(new_drift)} not in baseline):")
        for code in new_drift:
            if category == "required_ungranted":
                info = out["required_ungranted"][code]
                where = info["enforced_at"] or info["contract_modules"]
            elif category == "granted_unrequired":
                where = sorted(grants[code])
            elif category == "required_no_bit":
                where = sorted(enforced.get(code, []))[:3] or sorted(contract.get(code, []))
            else:  # granted_no_bit
                where = sorted(grants[code])
            print(f"  - {code}  [{category}]" + (f"  ({where})" if where else ""))

    if stale:
        failed = True
        print(f"\nSTALE BASELINE -- {category}: baseline entries that no longer drift -- "
              f"delete these lines from {baseline_path} ({len(stale)}):")
        for code in stale:
            print(f"  - {code}: {baselined[code]}")

unreachable_op_count = out["counts"]["unreachable_op_count"]
if unreachable_op_count > 0:
    failed = True
    print(f"\nNEW DRIFT -- unreachable_op_count ({unreachable_op_count}, never baselined, must be 0):")
    for mod, ops in sorted(unreachable_ops.items()):
        for path, method, perms in ops:
            print(f"  - {mod}: {method.upper()} {path}  requires {perms}")

print("\n-- informational only, not gated (see docs/rbac-permission-role-audit-2026-08.md §5) --")
print(f"  required_unregistered: {len(flag_required_unregistered)}")
print(f"  catalog_dead: {len(flag_catalog_dead)}")

if failed:
    print(f"\nFAIL: new authorization drift and/or a stale baseline entry -- see above.")
    print(f"Fix the drift, or add/remove a baseline entry with a reason: {baseline_path}")
    print(f"Background: docs/rbac-permission-role-audit-2026-08.md (§7, task 7)")
    sys.exit(1)

print(f"\nOK: no new authorization drift (baseline: {sum(len(baseline.get(c, {})) for c in gated)} accepted "
      f"exceptions across {len(gated)} categories, 0 unreachable ops).")
sys.exit(0)

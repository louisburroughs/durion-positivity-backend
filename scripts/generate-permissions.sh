#!/usr/bin/env bash
# Regenerate src/main/resources/permissions.yaml for modules by scanning
# @PreAuthorize annotations in Java source, then refresh the aggregate
# permissions report.
#
# This script is a thin wrapper around scripts/generate-permissions.py and
# scripts/export-permission-registrations-yaml.py. It is also called
# automatically by scripts/generate-openapi.sh at the end of each run
# (pass --no-permissions to generate-openapi.sh to disable that).
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AGGREGATE_OUTPUT="docs/permissions-report.yaml"
cd "$ROOT_DIR"

DRY_RUN=false
CHECK=false
SYNC=false
MODULES=()
GRANTS=()

usage() {
  cat <<'EOF'
Regenerate permissions.yaml for modules from @PreAuthorize annotations.

Usage:
  scripts/generate-permissions.sh [options] [module...]

Options:
  --sync         Reconcile PermissionCode.java, GatewayPermissionCatalog.java and
                 DownstreamPermissionCatalog.java, grant any newly registered
                 permission in R__seed_role_permissions.sql and the alpha role
                 baseline CSV, then regenerate permissions.yaml
  --grant ROLE   Role to grant newly registered permissions to (repeatable;
                 default ADMIN, overridden per permission by grantTo in the
                 owning module's permissions.yaml). Only meaningful with --sync.
  --dry-run      Print changes without writing files
  --check        Exit non-zero if any permissions.yaml would change, or (with
                 --sync) if permission catalogs differ or a bit-indexed,
                 @PreAuthorize-required permission is granted to no role (CI mode)
  -h, --help     Show this help

Examples:
  scripts/generate-permissions.sh
  scripts/generate-permissions.sh --sync
  scripts/generate-permissions.sh --sync --grant ADMIN --grant SERVICE_ADVISOR
  scripts/generate-permissions.sh pos-workorder pos-accounting
  scripts/generate-permissions.sh --dry-run
  scripts/generate-permissions.sh --sync --check
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=true; shift ;;
    --check)   CHECK=true;   shift ;;
    --sync)    SYNC=true;    shift ;;
    --grant)
      if [[ $# -lt 2 || -z "$2" ]]; then
        echo "--grant requires a role name" >&2
        exit 1
      fi
      GRANTS+=("$2"); shift 2 ;;
    --grant=*) GRANTS+=("${1#*=}"); shift ;;
    -h|--help) usage; exit 0 ;;
    pos-*)     MODULES+=("$1"); shift ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if ! command -v python3 >/dev/null 2>&1; then
  echo "ERROR: python3 is required" >&2
  exit 1
fi

PY_ARGS=("$ROOT_DIR")
[[ ${#MODULES[@]} -gt 0 ]] && PY_ARGS+=("${MODULES[@]}")
[[ "$DRY_RUN" == true ]] && PY_ARGS+=(--dry-run)
[[ "$CHECK"   == true ]] && PY_ARGS+=(--check)
[[ "$SYNC"    == true ]] && PY_ARGS+=(--sync)
for grant in ${GRANTS[@]+"${GRANTS[@]}"}; do
  PY_ARGS+=(--grant "$grant")
done

python3 "$SCRIPT_DIR/generate-permissions.py" "${PY_ARGS[@]}"

if [[ "$DRY_RUN" == false && "$CHECK" == false ]]; then
  python3 "$SCRIPT_DIR/export-permission-registrations-yaml.py" \
    --root "$ROOT_DIR" \
    --output "$AGGREGATE_OUTPUT"
fi

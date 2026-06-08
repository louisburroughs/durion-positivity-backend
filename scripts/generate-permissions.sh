#!/usr/bin/env bash
# Regenerate src/main/resources/permissions.yaml for modules by scanning
# @PreAuthorize annotations in Java source.
#
# This script is a thin wrapper around scripts/generate-permissions.py and
# is also called automatically by scripts/generate-openapi.sh at the end of
# each run (pass --no-permissions to generate-openapi.sh to disable that).
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

DRY_RUN=false
CHECK=false
SYNC=false
MODULES=()

usage() {
  cat <<'EOF'
Regenerate permissions.yaml for modules from @PreAuthorize annotations.

Usage:
  scripts/generate-permissions.sh [options] [module...]

Options:
  --sync      Register new @PreAuthorize permissions in PermissionCode.java and
              GatewayPermissionCatalog.java, then regenerate permissions.yaml
  --dry-run   Print changes without writing files
  --check     Exit non-zero if any permissions.yaml would change, or (with
              --sync) if any @PreAuthorize permissions are unregistered (CI mode)
  -h, --help  Show this help

Examples:
  scripts/generate-permissions.sh
  scripts/generate-permissions.sh --sync
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

python3 "$SCRIPT_DIR/generate-permissions.py" "${PY_ARGS[@]}"

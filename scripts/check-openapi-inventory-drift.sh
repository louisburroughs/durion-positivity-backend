#!/usr/bin/env bash
set -euo pipefail

# Guards against drift in pos-openapi-validation's module inventory.
#
# OpenApiRepositoryValidator only walks modules registered in
# src/test/resources/openapi/module-inventory.yaml. A module that generates an
# openapi.yaml but is absent from that inventory is validated by nothing: its
# spec can go missing, stop parsing, lose its paths section, or ship operations
# with no summary/description, and CI stays green. Four modules had drifted out
# this way before this check existed (#1243, #1262).
#
# Every module with a committed <module>/openapi.yaml must therefore appear in
# the inventory, except those listed in EXEMPT_MODULES below. The reverse
# direction is checked too: an inventory key that is not a reactor module is a
# stale entry left behind by a rename or removal.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

INVENTORY="pos-openapi-validation/src/test/resources/openapi/module-inventory.yaml"

if [[ ! -f "$INVENTORY" ]]; then
  echo "ERROR: inventory not found: ${INVENTORY}"
  exit 1
fi

# Modules that publish an openapi.yaml but are deliberately unregistered.
# Prefer an `EXCEPTION` entry with a reason in the inventory over an entry here:
# the inventory records why in the file that readers already consult. This list
# exists for specs that the validator cannot meaningfully load at all.
EXEMPT_MODULES=()

spec_modules=$(
  find . -maxdepth 2 -mindepth 2 -name openapi.yaml -not -path './target/*' \
    | sed 's|^\./||; s|/openapi\.yaml$||' \
    | sort -u
)

if [[ ${#EXEMPT_MODULES[@]} -gt 0 ]]; then
  exempt_pattern="$(printf '%s\n' "${EXEMPT_MODULES[@]}" | paste -sd '|' -)"
  spec_modules=$(echo "$spec_modules" | grep -Ev "^(${exempt_pattern})$" || true)
fi

# Module keys are the two-space-indented mapping keys under `modules:`; policy
# fields (`mode:`, `reason:`) sit one level deeper and are not matched.
inventory_modules=$(
  sed -n 's|^  \([A-Za-z0-9._-]*\):[[:space:]]*$|\1|p' "$INVENTORY" \
    | sort -u
)

reactor_modules=$(
  grep -o '<module>[^<]*</module>' pom.xml \
    | sed 's|</\?module>||g' \
    | sort -u
)

missing=$(comm -23 <(echo "$spec_modules") <(echo "$inventory_modules"))
stale=$(comm -23 <(echo "$inventory_modules") <(echo "$reactor_modules"))

status=0

if [[ -n "$missing" ]]; then
  echo "ERROR: modules with a generated openapi.yaml missing from ${INVENTORY} (their specs are validated by nothing):"
  echo "$missing" | sed 's/^/  - /'
  echo
  echo "Add each as a module entry with an explicit mode, e.g.:"
  echo "  pos-example:"
  echo "    mode: STRICT"
  echo
  echo "Registering at STRICT may surface real spec defects; fix those in the controller"
  echo "annotations the spec is generated from, not by lowering the mode. If a module"
  echo "cannot be made STRICT-clean now, REPORT_ONLY still beats absence."
  status=1
fi

if [[ -n "$stale" ]]; then
  echo "ERROR: entries in ${INVENTORY} that are not reactor modules of pom.xml:"
  echo "$stale" | sed 's/^/  - /'
  echo
  echo "Remove each stale entry, or add the module back to the root <modules> list."
  status=1
fi

if [[ "$status" -ne 0 ]]; then
  exit "$status"
fi

echo "PASS: ${INVENTORY} registers every spec-producing module ($(echo "$spec_modules" | wc -l | tr -d ' ') modules)."

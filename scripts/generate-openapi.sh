#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

MVNW="./mvnw"
PROFILE="local"
PHASE="verify"
SKIP_FLAGS=(-DskipTests -DskipITs)
MODULE_TIMEOUT_SECONDS=420
DRY_RUN=false

usage() {
  cat <<'EOF'
Generate openapi.yaml for modules configured with springdoc-openapi-maven-plugin.

Usage:
  scripts/generate-openapi.sh [options] [module...]

Options:
  --profile <name>   Maven profile to activate (default: local)
  --phase <phase>    Maven phase/goal to run (default: verify)
                     Note: verify ensures post-integration-test runs (spring-boot:stop).
  --timeout <sec>    Per-module timeout in seconds (default: 420, use 0 to disable)
  --run-tests        Do not skip tests
  --dry-run          Print commands without executing
  -h, --help         Show this help

Examples:
  scripts/generate-openapi.sh
  scripts/generate-openapi.sh pos-price pos-tax
  scripts/generate-openapi.sh --profile local --phase verify
EOF
}

MODULES=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile)
      PROFILE="$2"
      shift 2
      ;;
    --phase)
      PHASE="$2"
      shift 2
      ;;
    --run-tests)
      SKIP_FLAGS=()
      shift
      ;;
    --timeout)
      MODULE_TIMEOUT_SECONDS="$2"
      shift 2
      ;;
    --dry-run)
      DRY_RUN=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    pos-*)
      MODULES+=("$1")
      shift
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ ! -x "$MVNW" ]]; then
  echo "Cannot find executable Maven wrapper at $MVNW" >&2
  exit 1
fi

discover_modules() {
  rg -l '<outputFileName>openapi.yaml</outputFileName>' pos-*/pom.xml \
    | sort \
    | sed 's#/pom.xml$##'
}

module_has_openapi() {
  local module="$1"
  [[ -f "$module/pom.xml" ]] && rg -q '<outputFileName>openapi.yaml</outputFileName>' "$module/pom.xml"
}

if [[ ${#MODULES[@]} -eq 0 ]]; then
  mapfile -t MODULES < <(discover_modules)
fi

if [[ ${#MODULES[@]} -eq 0 ]]; then
  echo "No modules found with <outputFileName>openapi.yaml</outputFileName>."
  exit 0
fi

echo "Profile: $PROFILE"
echo "Phase:   $PHASE"
if [[ "$MODULE_TIMEOUT_SECONDS" -gt 0 ]]; then
  echo "Timeout: ${MODULE_TIMEOUT_SECONDS}s per module"
else
  echo "Timeout: disabled"
fi
if [[ ${#SKIP_FLAGS[@]} -eq 0 ]]; then
  echo "Tests:   enabled"
else
  echo "Tests:   skipped (${SKIP_FLAGS[*]})"
fi
echo "Modules: ${MODULES[*]}"
echo

for module in "${MODULES[@]}"; do
  if ! module_has_openapi "$module"; then
    echo "Skipping $module (no openapi.yaml output configured in pom.xml)"
    continue
  fi

  cmd=("$MVNW" "-pl" "$module" "-am" "-P$PROFILE" "${SKIP_FLAGS[@]}" "$PHASE")
  echo "==> $module"
  echo "${cmd[*]}"
  if [[ "$DRY_RUN" == false ]]; then
    if [[ "$MODULE_TIMEOUT_SECONDS" -gt 0 ]]; then
      if command -v timeout >/dev/null 2>&1; then
        if ! timeout --foreground "${MODULE_TIMEOUT_SECONDS}" "${cmd[@]}"; then
          status=$?
          if [[ "$status" -eq 124 ]]; then
            echo "ERROR: Timed out after ${MODULE_TIMEOUT_SECONDS}s while generating OpenAPI for $module." >&2
            echo "This usually indicates Spring Boot failed to start or startup never reached readiness." >&2
          fi
          exit "$status"
        fi
      else
        echo "WARN: 'timeout' command not found; running without timeout for $module." >&2
        "${cmd[@]}"
      fi
    else
      "${cmd[@]}"
    fi
  fi
  echo
done

echo "OpenAPI generation completed."

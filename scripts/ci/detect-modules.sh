#!/usr/bin/env bash
# Single source of truth for CI change detection (BACKEND_BUILD_SPEC.md §4.1).
#
# Emits one JSON object on stdout:
#   {"full_reactor": false, "modules": ["pos-order", "pos-price"]}
#   {"full_reactor": true,  "modules": []}
#
# modules is the deduplicated list of changed pos-* module directories, empty
# when full_reactor is true. Consumers must check full_reactor before looking
# at modules — an empty list only means "no module selection" when
# full_reactor is false (ci.yml then runs its ArchUnit-only fallback rather
# than a selective test pass; it never means the whole reactor is affected).
#
# Usage:
#   detect-modules.sh --full                    # unconditional full reactor
#   detect-modules.sh <diff-base> <diff-head>   # diff-driven detection

set -euo pipefail

# Modules that exist in the reactor but are never selected for testing/building
# on their own: the BOM has no code, the coverage aggregator only aggregates.
NON_SELECTABLE_MODULES='["pos-dependencies","pos-coverage-aggregate"]'

emit() {
  local full_reactor="$1" modules_json="$2"
  jq -cn --argjson full "${full_reactor}" --argjson modules "${modules_json}" \
    '{full_reactor: $full, modules: $modules}'
}

if [[ "${1:-}" == "--full" ]]; then
  emit true '[]'
  exit 0
fi

if [[ $# -ne 2 ]]; then
  echo "usage: $0 --full | <diff-base> <diff-head>" >&2
  exit 2
fi

DIFF_BASE="$1"
DIFF_HEAD="$2"

CHANGED_FILES="$(git diff --name-only "${DIFF_BASE}" "${DIFF_HEAD}")"

# Full-reactor triggers: files whose changes can affect how every module
# builds. Shared libraries are NOT listed — Maven's -amd flag rebuilds their
# dependents from the module selection instead (spec §4.1).
FULL_REACTOR_TRIGGERS='^(pom\.xml|\.mvn/|pos-dependencies/|build-tools/|\.github/workflows/ci\.yml|docker-compose\.yml)'

if echo "${CHANGED_FILES}" | grep -Eq "${FULL_REACTOR_TRIGGERS}"; then
  emit true '[]'
  exit 0
fi

MODULES_JSON="$(
  echo "${CHANGED_FILES}" \
    | { grep -E '^pos-[^/]+/' || true; } \
    | cut -d'/' -f1 \
    | sort -u \
    | while read -r module; do
        [[ -n "${module}" && -f "${module}/pom.xml" ]] && echo "${module}"
      done \
    | jq -R -s -c --argjson excluded "${NON_SELECTABLE_MODULES}" \
        'split("\n")[:-1] | map(select((. as $m | $excluded | index($m)) | not))'
)"

emit false "${MODULES_JSON}"

#!/usr/bin/env bash
set -euo pipefail

# Asserts that deployment/alpha/docker-compose.accelerated.yml puts EVERY POS JVM on the
# accelerated clock, by merging the real compose files with `docker compose config` and
# reading the result (#2065).
#
# Why the merged config and not the override on its own: the override is the file under
# review, but what a service actually runs with is the three files merged. This is the only
# check that sees what the deploy sees — that compose MERGES the environment mapping rather
# than replacing it, so a service keeps its datasource and Kafka variables AND gains the
# profile and the anchors.
#
# Why coverage matters more than the file's contents: one service left on the wall clock
# writes records a year ahead of everything else in the same database during a year-long
# seeding run, and nothing fails at the time. The expected set is derived from
# docker-compose.yml's build contexts (the same pivot scripts/check-deploy-service-drift.sh
# uses), so adding a pos-* service to the stack without adding it here is a red build here
# rather than a silent gap on alpha.
#
# Run: ./scripts/check-accelerated-compose.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

COMPOSE="docker-compose.yml"
PROD_OVERRIDE="deployment/alpha/docker-compose.prod.yml"
ACCELERATED_OVERRIDE="deployment/alpha/docker-compose.accelerated.yml"

# Backend compose services that deliberately stay on the wall clock. Keep in step with the
# same list in the override's header comment.
#
#   eureka-server      - a service registry writes no business timestamps.
#   pos-reference-mock - a mock external vendor, likewise; a fake vendor answering in virtual
#                        time would be modelling something no real vendor does.
EXCLUDED_SERVICES=(eureka-server pos-reference-mock)

# Anchors every accelerated service must carry. REAL_START and VIRTUAL_START are the two the
# override marks required; the other three have defaults but must still reach every JVM,
# since a service falling back to a different default is the skew this exists to prevent.
REQUIRED_KEYS=(
  SPRING_PROFILES_INCLUDE
  POS_TIME_ACCELERATED_SCALE
  POS_TIME_ACCELERATED_ZONE
  POS_TIME_ACCELERATED_CONVERGE
  POS_TIME_ACCELERATED_REAL_START
  POS_TIME_ACCELERATED_VIRTUAL_START
)

# Any valid pair satisfying the compose interpolation; this check is about coverage, and
# deploy-backend.sh owns the one-day floor and the rest of the anchor validation.
PROBE_REAL_START="2026-09-17T12:00:00Z"
PROBE_VIRTUAL_START="2025-09-17T12:00:00Z"

for f in "$COMPOSE" "$PROD_OVERRIDE" "$ACCELERATED_OVERRIDE"; do
  if [[ ! -f "$f" ]]; then
    echo "ERROR: expected file not found: ${f}" >&2
    exit 1
  fi
done

if ! docker compose version >/dev/null 2>&1; then
  echo "ERROR: 'docker compose' is required to merge the compose files." >&2
  exit 1
fi

status=0

# ── 1. A missing anchor must fail the compose invocation ─────────────────────
#
# `${VAR:?...}` in the override is the last line of defence: it stops a deploy that lost an
# anchor from starting ONE service unaccelerated while the rest of the stack is fine. That
# only holds while the required-variable syntax is actually there, which nothing else checks.
echo "Checking a missing anchor fails 'docker compose config'"
for missing in POS_TIME_ACCELERATED_REAL_START POS_TIME_ACCELERATED_VIRTUAL_START; do
  # Supply every anchor EXCEPT the one under test. `env -u NAME NAME=value` would put it
  # straight back — the assignment operands are applied after the unsets — so the variable
  # has to be left out of the list rather than removed from it.
  env_args=()
  [[ "${missing}" != "POS_TIME_ACCELERATED_REAL_START" ]] \
    && env_args+=("POS_TIME_ACCELERATED_REAL_START=${PROBE_REAL_START}")
  [[ "${missing}" != "POS_TIME_ACCELERATED_VIRTUAL_START" ]] \
    && env_args+=("POS_TIME_ACCELERATED_VIRTUAL_START=${PROBE_VIRTUAL_START}")

  if out="$(env -u "${missing}" "${env_args[@]}" \
      docker compose -f "$COMPOSE" -f "$PROD_OVERRIDE" -f "$ACCELERATED_OVERRIDE" config -q 2>&1)"; then
    echo "ERROR: 'docker compose config' succeeded with ${missing} unset." >&2
    echo "  Restore the \${${missing}:?...} required-variable syntax in ${ACCELERATED_OVERRIDE};" >&2
    echo "  without it a deploy missing that anchor starts services on inconsistent clocks." >&2
    status=1
  elif ! grep -q "${missing}" <<< "${out}"; then
    echo "ERROR: 'docker compose config' failed with ${missing} unset, but did not name it:" >&2
    sed 's/^/    /' <<< "${out}" >&2
    status=1
  else
    echo "  ok   unset ${missing} is refused, by name"
  fi
done

# ── 2. Every POS JVM carries the profile and all five anchors ────────────────

MERGED="$(mktemp)"
trap 'rm -f "${MERGED}"' EXIT

if ! POS_TIME_ACCELERATED_REAL_START="${PROBE_REAL_START}" \
     POS_TIME_ACCELERATED_VIRTUAL_START="${PROBE_VIRTUAL_START}" \
     docker compose -f "$COMPOSE" -f "$PROD_OVERRIDE" -f "$ACCELERATED_OVERRIDE" \
     config --format json > "${MERGED}" 2>/dev/null; then
  echo "ERROR: could not merge the compose files with the accelerated override." >&2
  POS_TIME_ACCELERATED_REAL_START="${PROBE_REAL_START}" \
  POS_TIME_ACCELERATED_VIRTUAL_START="${PROBE_VIRTUAL_START}" \
    docker compose -f "$COMPOSE" -f "$PROD_OVERRIDE" -f "$ACCELERATED_OVERRIDE" config -q >&2 || true
  exit 1
fi

# The backend services: every compose service built from a ./pos-* directory. Same pivot as
# scripts/check-deploy-service-drift.sh, so the two agree on what "a deployed service" is.
backend_services="$(
  awk '
    /^[A-Za-z_][A-Za-z0-9_-]*:/ { in_services = ($0 ~ /^services:[[:space:]]*$/); next }
    !in_services { next }
    /^  [A-Za-z0-9_.-]+:[[:space:]]*$/ { svc = $0; sub(/:[[:space:]]*$/, "", svc); sub(/^  /, "", svc); next }
    /^[[:space:]]+context:[[:space:]]*\.\// {
      ctx = $2; sub(/^\.\//, "", ctx)
      if (svc != "" && ctx ~ /^pos-[A-Za-z0-9-]+$/) { print svc; svc = "" }
    }
  ' "$COMPOSE" | sort -u
)"

expected="$(printf '%s\n' "${backend_services}" | grep -vxF -f <(printf '%s\n' "${EXCLUDED_SERVICES[@]}") || true)"
expected_count="$(grep -c . <<< "${expected}" || true)"

if [[ "${expected_count}" -eq 0 ]]; then
  echo "ERROR: parsed no backend services out of ${COMPOSE}." >&2
  exit 1
fi

echo
echo "Checking ${expected_count} POS JVMs carry the profile and all five anchors in the merged config"

# Prints "<service> <missing-key>..." for each service missing any required key, and
# "<service> <key>=<value>" lines for the anchors, so the caller can diff them.
report="$(
  MERGED_FILE="${MERGED}" EXPECTED="${expected}" REQUIRED="$(printf '%s\n' "${REQUIRED_KEYS[@]}")" \
  python3 - <<'PY'
import json, os, sys

merged = json.load(open(os.environ["MERGED_FILE"]))
services = merged.get("services", {})
expected = [s for s in os.environ["EXPECTED"].split("\n") if s]
required = [k for k in os.environ["REQUIRED"].split("\n") if k]

problems = []
anchors = {}

for name in expected:
    service = services.get(name)
    if service is None:
        problems.append(f"{name}: no such service in the merged config")
        continue
    env = service.get("environment") or {}
    missing = [k for k in required if k not in env]
    if missing:
        problems.append(f"{name}: missing {', '.join(missing)}")
        continue
    if "accelerated" not in str(env["SPRING_PROFILES_INCLUDE"]).split(","):
        problems.append(
            f"{name}: SPRING_PROFILES_INCLUDE is {env['SPRING_PROFILES_INCLUDE']!r}, "
            "expected to include 'accelerated'"
        )
    anchors[name] = tuple(env[k] for k in required)

# Every service must agree, not merely carry something. A per-service override of any anchor
# is the skew this file exists to prevent: at scale 1460 a one-second disagreement between
# two JVMs is 24 virtual minutes of disagreement about when a record was written.
distinct = set(anchors.values())
if len(distinct) > 1:
    problems.append("services do not all share the same anchors:")
    for value in sorted(distinct):
        owners = sorted(n for n, v in anchors.items() if v == value)
        problems.append(f"  {dict(zip(required, value))} <- {', '.join(owners)}")

# The reverse direction: a service named in the override that no longer exists, or one that
# should be excluded, is dead configuration.
accelerated_in_merged = sorted(
    n for n, s in services.items()
    if "POS_TIME_ACCELERATED_REAL_START" in (s.get("environment") or {})
)
unexpected = sorted(set(accelerated_in_merged) - set(expected))
if unexpected:
    problems.append(
        "services on the accelerated clock that are not POS JVMs, or are on the "
        f"deliberate exclusion list: {', '.join(unexpected)}"
    )

print(len(accelerated_in_merged))
for p in problems:
    print(p)
PY
)"

actual_count="$(head -n1 <<< "${report}")"
problems="$(tail -n +2 <<< "${report}")"

if [[ -n "${problems}" ]]; then
  echo "ERROR: the accelerated override does not cover every POS JVM:" >&2
  sed 's/^/  /' <<< "${problems}" >&2
  echo >&2
  echo "  Add the service to ${ACCELERATED_OVERRIDE} with 'environment: *accelerated-env'," >&2
  echo "  or add it to EXCLUDED_SERVICES in $0 with a reason (a service that writes no" >&2
  echo "  business timestamps). A POS JVM left on the wall clock during an accelerated run" >&2
  echo "  writes records a year ahead of every other service in the same database." >&2
  status=1
fi

if [[ "${actual_count}" != "${expected_count}" ]]; then
  echo "ERROR: ${actual_count} services carry POS_TIME_ACCELERATED_REAL_START; expected ${expected_count}." >&2
  status=1
fi

if [[ "${status}" -eq 0 ]]; then
  echo "  ok   all ${actual_count} carry the profile and the same five anchors"
  echo
  echo "PASS: ${actual_count} POS JVMs are on the accelerated clock, ${#EXCLUDED_SERVICES[@]} deliberately excluded"
fi

exit "${status}"

#!/usr/bin/env bash
set -euo pipefail

# Self-test for deploy-backend.sh's pre-pull disk reclaim (#1862).
#
# run_periodic_docker_prune is the last line of deploy-backend.sh, so it never runs on a
# deploy that failed. When the alpha box filled up mid-pull, that turned one failure into a
# permanent one: every later deploy died at the same pull, and the cleanup that would have
# fixed it was unreachable without a human on the host. reclaim_disk_before_pull exists to
# keep that deadlock from forming, and this drives it against a stubbed `docker` and `df`.
#
# Cases:
#   1. free space at/above the floor  -> no prune
#   2. free space below the floor     -> prune runs, state file stamped
#   3. prune itself fails             -> warned, returns 0, state file NOT stamped
#   4. DOCKER_MIN_FREE_GIB=0          -> disabled, no prune
#   5. DOCKER_MIN_FREE_GIB non-integer-> skipped, no prune
#   6. df cannot read the data root   -> skipped, no prune
#
# Run: bash scripts/tests/deploy-backend-disk-reclaim-selftest.sh

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="${REPO_ROOT}/deployment/alpha/deploy-backend.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

FAILURES=0

# Slice out just the reclaim functions. deploy-backend.sh runs a deploy top-to-bottom, so it
# cannot be sourced whole. The assertion below is the point of the slice: if these functions
# are renamed or reordered, the extraction fails loudly instead of testing an empty file.
extract_functions() {
  awk '
    /^docker_data_root\(\) \{/ { inside = 1 }
    inside { print }
    /^reclaim_disk_before_pull\(\) \{/ { closing = 1 }
    closing && /^\}$/ { exit }
  ' "${SCRIPT}" > "${WORK}/functions.sh"

  local fn
  for fn in docker_data_root free_gib_for_path reclaim_disk_before_pull; do
    if ! grep -q "^${fn}() {" "${WORK}/functions.sh"; then
      echo "FATAL: could not extract ${fn} from ${SCRIPT} — the self-test would assert nothing." >&2
      exit 1
    fi
  done
  bash -n "${WORK}/functions.sh"
}

make_stubs() {
  mkdir -p "${WORK}/bin"

  cat > "${WORK}/bin/docker" <<'STUB'
#!/usr/bin/env bash
set -uo pipefail
echo "docker $*" >> "${LOG}"
case "${1:-} ${2:-}" in
  "info --format") echo "${STUB_DATA_ROOT:-/var/lib/docker}" ;;
  "system prune")  exit "${PRUNE_EXIT:-0}" ;;
  "system df")     echo "stub docker system df" ;;
esac
exit 0
STUB

  # $4 is the free-KiB column the script reads. FREE_KIB empty means "df failed".
  cat > "${WORK}/bin/df" <<'STUB'
#!/usr/bin/env bash
set -uo pipefail
if [[ -z "${FREE_KIB:-}" ]]; then
  echo "df: unreadable" >&2
  exit 1
fi
echo "Filesystem 1024-blocks Used Available Capacity Mounted on"
echo "/dev/xvda1 104857600 1000 ${FREE_KIB} 99% /"
STUB

  chmod +x "${WORK}/bin/docker" "${WORK}/bin/df"
}

# run_case <name> <expect_prune:yes|no> <expect_stamp:yes|no> <env assignments...>
run_case() {
  local name="$1" expect_prune="$2" expect_stamp="$3"; shift 3
  local log="${WORK}/${name}.log" out="${WORK}/${name}.out" state="${WORK}/${name}.stamp"

  : > "${log}"
  local rc=0
  env -i PATH="${WORK}/bin:/usr/bin:/bin" \
      LOG="${log}" \
      DOCKER_PRUNE_STATE_FILE="${state}" \
      "$@" \
      bash -c 'source "$0"; reclaim_disk_before_pull' "${WORK}/functions.sh" > "${out}" 2>&1 || rc=$?

  if [[ "${rc}" -ne 0 ]]; then
    echo "FAIL ${name}: reclaim_disk_before_pull returned ${rc}, expected 0 (it must never abort a deploy)"
    sed 's/^/      /' "${out}"
    FAILURES=$((FAILURES + 1))
    return
  fi

  local pruned=no
  grep -q "^docker system prune -af$" "${log}" && pruned=yes

  if [[ "${pruned}" != "${expect_prune}" ]]; then
    echo "FAIL ${name}: expected prune=${expect_prune}, got prune=${pruned}"
    echo "    --- docker calls ---"; sed 's/^/      /' "${log}"
    echo "    --- output ---";       sed 's/^/      /' "${out}"
    FAILURES=$((FAILURES + 1))
    return
  fi

  # A prune that succeeded must stamp the state file, so the post-deploy periodic prune does
  # not immediately repeat the work this one just did. A prune that FAILED must not stamp it:
  # recording a cleanup that never happened would suppress the next 24h of periodic prunes.
  local stamped=no
  [[ -s "${state}" ]] && stamped=yes
  if [[ "${stamped}" != "${expect_stamp}" ]]; then
    echo "FAIL ${name}: expected stamped=${expect_stamp}, got stamped=${stamped}"
    FAILURES=$((FAILURES + 1))
    return
  fi

  echo "PASS ${name}"
}

assert_output_contains() {
  local name="$1" needle="$2"
  if ! grep -qF -- "${needle}" "${WORK}/${name}.out"; then
    echo "FAIL ${name}: output did not mention '${needle}'"
    sed 's/^/      /' "${WORK}/${name}.out"
    FAILURES=$((FAILURES + 1))
  fi
}

extract_functions
make_stubs

# 60GiB free, floor 25 -> nothing to do.
run_case above-floor no no FREE_KIB=$((60 * 1048576)) DOCKER_MIN_FREE_GIB=25
assert_output_contains above-floor "60GiB free"

# 3GiB free, floor 25 -> reclaim.
run_case below-floor yes yes FREE_KIB=$((3 * 1048576)) DOCKER_MIN_FREE_GIB=25
assert_output_contains below-floor "under the 25GiB floor"

# The prune failing must not take the deploy down with it.
run_case prune-fails yes no FREE_KIB=$((3 * 1048576)) DOCKER_MIN_FREE_GIB=25 PRUNE_EXIT=1
assert_output_contains prune-fails "pre-pull docker system prune failed"

run_case disabled     no no FREE_KIB=$((1 * 1048576)) DOCKER_MIN_FREE_GIB=0
run_case non-integer  no no FREE_KIB=$((1 * 1048576)) DOCKER_MIN_FREE_GIB=twenty
assert_output_contains non-integer "must be an integer"

# df failing is a reason to skip, never a reason to prune blindly or to fail the deploy.
run_case df-unreadable no no DOCKER_MIN_FREE_GIB=25
assert_output_contains df-unreadable "could not read free space"

echo
if [[ "${FAILURES}" -eq 0 ]]; then
  echo "deploy-backend disk-reclaim self-test: all cases passed"
else
  echo "deploy-backend disk-reclaim self-test: ${FAILURES} failure(s)"
  exit 1
fi

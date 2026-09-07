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
#   6. df cannot read the data root   -> skipped, no prune, deploy NOT aborted
#   7. the shipped DOCKER_MIN_FREE_GIB default, above and below the floor
#   8. `docker info` failing (daemon down) -> data root falls back cleanly
#   9. a non-default Docker data root  -> that path is measured and reported
#  10. no containers running          -> prune still runs, but says it clears every image
#  11. prune frees too little         -> warned rather than reported as success
#  12. prune state file unwritable    -> warned, returns 0
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

  # Carry the shipped DOCKER_MIN_FREE_GIB default in too. Cases that set it explicitly can
  # never catch a bad default, and under `set -u` an unset one would abort.
  local default_line
  default_line="$(grep -m1 '^DOCKER_MIN_FREE_GIB=' "${SCRIPT}" || true)"
  if [[ -z "${default_line}" ]]; then
    echo "FATAL: could not find the DOCKER_MIN_FREE_GIB default in ${SCRIPT}." >&2
    exit 1
  fi
  printf '%s\n' "${default_line}" | cat - "${WORK}/functions.sh" > "${WORK}/functions.tmp"
  mv "${WORK}/functions.tmp" "${WORK}/functions.sh"

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
  "info --format")
    # DAEMON_DOWN models a client-only `docker info`: an empty line on stdout AND non-zero.
    if [[ -n "${DAEMON_DOWN:-}" ]]; then echo ""; exit 1; fi
    echo "${STUB_DATA_ROOT:-/var/lib/docker}"
    ;;
  "system prune")
    # A real prune frees space; FREE_KIB_AFTER lets a case say how much.
    [[ -n "${FREE_KIB_AFTER:-}" ]] && echo "${FREE_KIB_AFTER}" > "${WORK_FREE_FILE}"
    exit "${PRUNE_EXIT:-0}"
    ;;
  "system df") echo "stub docker system df" ;;
  "ps -q")
    for _ in $(seq 1 "${RUNNING_CONTAINERS:-3}"); do echo "deadbeefcafe"; done
    ;;
esac
exit 0
STUB

  # $4 is the free-KiB column the script reads. FREE_KIB empty means "df failed".
  cat > "${WORK}/bin/df" <<'STUB'
#!/usr/bin/env bash
set -uo pipefail
# Real df fails on a path that does not exist, so a stub that ignores its argument cannot
# catch a caller that builds a malformed data root (an empty `docker info` line concatenated
# onto the fallback, say). Reject anything that is not a single clean absolute path.
path=""
for arg in "$@"; do [[ "${arg}" == -* ]] || path="${arg}"; done
if [[ "${path}" != /* || "${path}" == *$'\n'* || "${path}" == *$'\r'* ]]; then
  echo "df: ${path}: No such file or directory" >&2
  exit 1
fi
free="${FREE_KIB:-}"
# After a prune the stub reports whatever that prune "freed", so the post-reclaim re-check
# sees a different number than the pre-check — as it does on a real box.
if [[ -n "${WORK_FREE_FILE:-}" && -s "${WORK_FREE_FILE}" ]]; then
  free="$(cat "${WORK_FREE_FILE}")"
fi
if [[ -z "${free}" ]]; then
  echo "df: unreadable" >&2
  exit 1
fi
echo "Filesystem 1024-blocks Used Available Capacity Mounted on"
echo "/dev/xvda1 209715200 1000 ${free} 99% /"
STUB

  chmod +x "${WORK}/bin/docker" "${WORK}/bin/df"
}

# run_case <name> <expect_prune:yes|no> <expect_stamp:yes|no> <env assignments...>
run_case() {
  local name="$1" expect_prune="$2" expect_stamp="$3"; shift 3
  local log="${WORK}/${name}.log" out="${WORK}/${name}.out" state="${WORK}/${name}.stamp"
  local freefile="${WORK}/${name}.free"

  : > "${log}"
  : > "${freefile}"
  local rc=0
  # `set -euo pipefail` is what deploy-backend.sh runs under, and shell options do not cross
  # a new bash process (env -i strips SHELLOPTS besides). Without this the harness runs the
  # reclaim in a permissive shell where a failing df yields status 0 and a non-zero assignment
  # does not abort — so it could not observe the one failure mode that matters.
  env -i PATH="${WORK}/bin:/usr/bin:/bin" \
      LOG="${log}" \
      WORK_FREE_FILE="${freefile}" \
      DOCKER_PRUNE_STATE_FILE="${state}" \
      "$@" \
      bash -c 'set -euo pipefail; source "$0"; reclaim_disk_before_pull' "${WORK}/functions.sh" > "${out}" 2>&1 || rc=$?

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
# Under `set -euo pipefail` the bare assignment from a failing pipeline is what would abort,
# so this case is the guard on the "never aborts a deploy" contract.
run_case df-unreadable no no DOCKER_MIN_FREE_GIB=25
assert_output_contains df-unreadable "could not read free space"

# The shipped default, exercised with DOCKER_MIN_FREE_GIB unset (also the `set -u` check).
run_case default-above no  no FREE_KIB=$((60 * 1048576))
run_case default-below yes yes FREE_KIB=$((10 * 1048576)) FREE_KIB_AFTER=$((90 * 1048576))

# A client-only `docker info` prints an empty line AND exits non-zero. Falling back by
# concatenation would make the data root '\n/var/lib/docker' and df would fail on it.
run_case daemon-down yes yes FREE_KIB=$((3 * 1048576)) FREE_KIB_AFTER=$((90 * 1048576)) DAEMON_DOWN=1
assert_output_contains daemon-down "/var/lib/docker"

# A non-default data root must be the path that is measured and reported.
run_case custom-data-root no no FREE_KIB=$((60 * 1048576)) DOCKER_MIN_FREE_GIB=25 STUB_DATA_ROOT=/mnt/docker
assert_output_contains custom-data-root "/mnt/docker"

# Right after a reboot nothing is running, so a prune clears every image. That is still the
# right trade under the floor, but it must be said out loud in the log.
run_case no-containers yes yes FREE_KIB=$((3 * 1048576)) FREE_KIB_AFTER=$((90 * 1048576)) \
    DOCKER_MIN_FREE_GIB=25 RUNNING_CONTAINERS=0
assert_output_contains no-containers "remove every image on the box"

# A prune that frees almost nothing must not read like success.
run_case still-below-floor yes yes FREE_KIB=$((3 * 1048576)) FREE_KIB_AFTER=$((4 * 1048576)) \
    DOCKER_MIN_FREE_GIB=25
assert_output_contains still-below-floor "still 4GiB free after reclaim"

# An unwritable prune state file must warn, not abort mid-deploy: `set -e` is suspended for
# an `if` condition but not inside its body, and this write lives in the body.
UNWRITABLE="${WORK}/no-such-dir/stamp"
UNWRITABLE_OUT="${WORK}/unwritable-stamp.out"
UNWRITABLE_LOG="${WORK}/unwritable-stamp.log"
: > "${UNWRITABLE_LOG}"
rc=0
env -i PATH="${WORK}/bin:/usr/bin:/bin" \
    LOG="${UNWRITABLE_LOG}" \
    WORK_FREE_FILE="${WORK}/unwritable-stamp.free" \
    DOCKER_PRUNE_STATE_FILE="${UNWRITABLE}" \
    FREE_KIB=$((3 * 1048576)) FREE_KIB_AFTER=$((90 * 1048576)) DOCKER_MIN_FREE_GIB=25 \
    bash -c 'set -euo pipefail; source "$0"; reclaim_disk_before_pull' "${WORK}/functions.sh" \
    > "${UNWRITABLE_OUT}" 2>&1 || rc=$?
if [[ "${rc}" -ne 0 ]]; then
  echo "FAIL unwritable-stamp: returned ${rc}, expected 0"
  sed 's/^/      /' "${UNWRITABLE_OUT}"
  FAILURES=$((FAILURES + 1))
elif ! grep -q "could not stamp" "${UNWRITABLE_OUT}"; then
  echo "FAIL unwritable-stamp: no warning about the unstamped state file"
  sed 's/^/      /' "${UNWRITABLE_OUT}"
  FAILURES=$((FAILURES + 1))
else
  echo "PASS unwritable-stamp"
fi

echo
if [[ "${FAILURES}" -eq 0 ]]; then
  echo "deploy-backend disk-reclaim self-test: all cases passed"
else
  echo "deploy-backend disk-reclaim self-test: ${FAILURES} failure(s)"
  exit 1
fi

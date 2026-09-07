#!/usr/bin/env bash
set -euo pipefail

# Self-test for deployment/alpha/install-cloudwatch-agent.sh (#1862).
#
# The script is the only thing standing between the alpha box and a repeat of #1862, where the disk
# filled with nothing watching it. Its contract is that every condition leaving the box unwatched
# fails the run: a stopped agent, an unconfigured agent, an agent not enabled at boot, a malformed
# config. Those are exactly the paths that are never exercised by a successful hand-run, so they are
# driven here against stubbed dnf / rpm / systemctl / agent-ctl.
#
# Cases:
#   1. happy path                      -> exit 0, config installed, agent started
#   2. agent already installed         -> exit 0, dnf install never called
#   3. config file missing             -> exit 1, names the path
#   4. config is not valid JSON        -> exit 1, and the live config is NOT touched
#   5. fetch-config fails              -> exit 1, says what state the agent is left in
#   6. agent reports stopped           -> exit 1
#   7. agent reports no configuration  -> exit 1
#   8. agent not enabled at boot       -> exit 1
#   9. agent-ctl missing after install -> exit 1
#  10. not run as root                 -> exit 1
#
# Run: bash scripts/tests/install-cloudwatch-agent-selftest.sh

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="${REPO_ROOT}/deployment/alpha/install-cloudwatch-agent.sh"
REAL_CONFIG="${REPO_ROOT}/deployment/alpha/cloudwatch-agent-config.json"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

FAILURES=0

make_stubs() {
  mkdir -p "${WORK}/bin" "${WORK}/agent/bin" "${WORK}/agent/etc"

  cat > "${WORK}/bin/dnf" <<'STUB'
#!/usr/bin/env bash
echo "dnf $*" >> "${LOG}"
exit 0
STUB

  cat > "${WORK}/bin/rpm" <<'STUB'
#!/usr/bin/env bash
echo "rpm $*" >> "${LOG}"
[[ -n "${ALREADY_INSTALLED:-}" ]] || exit 1
echo "amazon-cloudwatch-agent-1.300067.1-1.amzn2023.x86_64"
STUB

  cat > "${WORK}/bin/systemctl" <<'STUB'
#!/usr/bin/env bash
echo "systemctl $*" >> "${LOG}"
case "${1:-}" in
  is-enabled) [[ -n "${NOT_ENABLED:-}" ]] && exit 1; exit 0 ;;
esac
exit 0
STUB

  # Stands in for amazon-cloudwatch-agent-ctl. Reports whatever the case asks it to.
  cat > "${WORK}/agent/bin/amazon-cloudwatch-agent-ctl" <<'STUB'
#!/usr/bin/env bash
echo "agentctl $*" >> "${LOG}"
for arg in "$@"; do
  if [[ "${arg}" == "fetch-config" ]]; then
    [[ -n "${FETCH_FAILS:-}" ]] && exit 1
    exit 0
  fi
  if [[ "${arg}" == "status" ]]; then
    printf '{\n  "status": "%s",\n  "configstatus": "%s",\n  "version": "1.300067.1"\n}\n' \
      "${AGENT_STATUS:-running}" "${AGENT_CONFIGSTATUS:-configured}"
    exit 0
  fi
done
exit 0
STUB

  chmod +x "${WORK}/bin/"* "${WORK}/agent/bin/amazon-cloudwatch-agent-ctl"
}

# run_case <name> <expected rc> [env assignments...]
run_case() {
  local name="$1" want_rc="$2"; shift 2
  local out="${WORK}/${name}.out" log="${WORK}/${name}.log"
  local etc="${WORK}/agent/etc"

  : > "${log}"
  rm -f "${etc}/durion-alpha.json"

  # EUID is readonly in bash, so root cannot be faked by assignment. Every case except the
  # not-root one runs the body through a shim that skips only that guard.
  local script="${SCRIPT}"
  if [[ "${name}" != "not-root" ]]; then
    script="${WORK}/${name}.sh"
    sed 's|^\[\[ "${EUID}" -eq 0 \]\].*|: # root guard skipped by the self-test|' "${SCRIPT}" > "${script}"
  fi

  local rc=0
  env PATH="${WORK}/bin:/usr/bin:/bin" \
      LOG="${log}" \
      AGENT_DIR_OVERRIDE="${WORK}/agent" \
      "$@" \
      bash "${script}" "${CONFIG_ARG:-${REAL_CONFIG}}" > "${out}" 2>&1 || rc=$?

  if [[ "${rc}" -ne "${want_rc}" ]]; then
    echo "FAIL ${name}: exit ${rc}, expected ${want_rc}"
    sed 's/^/      /' "${out}"
    FAILURES=$((FAILURES + 1))
    return 1
  fi
  echo "PASS ${name}"
  return 0
}

assert_out() {
  local name="$1" needle="$2"
  if ! grep -qiF -- "${needle}" "${WORK}/${name}.out"; then
    echo "FAIL ${name}: output did not mention '${needle}'"
    sed 's/^/      /' "${WORK}/${name}.out"
    FAILURES=$((FAILURES + 1))
  fi
}

assert_log() {
  local name="$1" pattern="$2" want="$3"
  local got=no
  grep -qE "${pattern}" "${WORK}/${name}.log" && got=yes
  if [[ "${got}" != "${want}" ]]; then
    echo "FAIL ${name}: expected '${pattern}' present=${want}, got ${got}"
    sed 's/^/      /' "${WORK}/${name}.log"
    FAILURES=$((FAILURES + 1))
  fi
}

# The script hard-codes /opt/aws/..., which a self-test must not write to. Redirect those paths at
# the copy level so the real file stays the thing under test.
prepare_script() {
  local tmp="${WORK}/under-test.sh"
  sed "s|^AGENT_DIR=/opt/aws/amazon-cloudwatch-agent$|AGENT_DIR=${WORK}/agent|" "${SCRIPT}" > "${tmp}"
  if ! grep -q "^AGENT_DIR=${WORK}/agent$" "${tmp}"; then
    echo "FATAL: could not redirect AGENT_DIR in ${SCRIPT} — the self-test would write to /opt/aws." >&2
    exit 1
  fi
  cp "${tmp}" "${SCRIPT}.selftest"
}

make_stubs
prepare_script
SCRIPT="${SCRIPT}.selftest"
trap 'rm -rf "${WORK}" "${SCRIPT}"' EXIT

run_case happy 0 && {
  assert_out happy "enabled at boot"
  assert_log happy "^dnf install" yes
  [[ -f "${WORK}/agent/etc/durion-alpha.json" ]] || {
    echo "FAIL happy: config was never installed to the agent's etc"
    FAILURES=$((FAILURES + 1))
  }
}

run_case already-installed 0 ALREADY_INSTALLED=1 && assert_log already-installed "^dnf install" no

CONFIG_ARG="${WORK}/does-not-exist.json" run_case missing-config 1 && assert_out missing-config "Config not found"

echo '{ this is not json' > "${WORK}/bad.json"
CONFIG_ARG="${WORK}/bad.json" run_case bad-json 1 && {
  assert_out bad-json "not valid JSON"
  # The live config must be untouched: fetch-config replaces the running configuration, so a
  # malformed file caught late leaves the agent with neither the old config nor a working new one.
  [[ -f "${WORK}/agent/etc/durion-alpha.json" ]] && {
    echo "FAIL bad-json: the live config was overwritten before validation"
    FAILURES=$((FAILURES + 1))
  }
  assert_log bad-json "fetch-config" no
}

run_case fetch-fails 1 FETCH_FAILS=1 && assert_out fetch-fails "fetch-config failed"
run_case agent-stopped 1 AGENT_STATUS=stopped && assert_out agent-stopped "not running"
run_case unconfigured 1 AGENT_CONFIGSTATUS=not_configured && assert_out unconfigured "no configuration"
run_case not-enabled 1 NOT_ENABLED=1 && assert_out not-enabled "not enabled at boot"

rm -f "${WORK}/agent/bin/amazon-cloudwatch-agent-ctl"
run_case no-ctl 1 ALREADY_INSTALLED=1 && assert_out no-ctl "control binary missing"
make_stubs

# Only meaningful when the suite itself is not root. CI runners are not, but a container shell is.
if [[ "${EUID}" -eq 0 ]]; then
  echo "SKIP not-root (this suite is running as root, so the guard cannot be observed)"
else
  run_case not-root 1 && assert_out not-root "Must run as root"
fi

echo
if [[ "${FAILURES}" -eq 0 ]]; then
  echo "install-cloudwatch-agent self-test: all cases passed"
else
  echo "install-cloudwatch-agent self-test: ${FAILURES} failure(s)"
  exit 1
fi

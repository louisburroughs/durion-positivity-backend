#!/usr/bin/env bash
set -euo pipefail

# Install and configure the CloudWatch agent on the alpha host (#1862 follow-up).
#
# Alpha filled its root disk and wedged: every deploy failed on "no space left on device", and
# because a full root filesystem also stops the SSM agent writing its output, there was no remote
# shell left to diagnose it with. Nothing alarmed, because no host metrics were published at all —
# EC2 publishes CPU and network from the hypervisor, but disk and memory live inside the instance
# and need an agent there.
#
# This publishes the two that would have caught it and nothing else: disk on / (where Docker's data
# root lives) and memory.
#
# Idempotent. The sync-alpha-config workflow runs it on every push that touches the config, so a
# committed edit reaches the box instead of sitting inert in git.
#
# Usage (on the box, as root):
#   sudo bash /opt/durion/alpha/scripts/install-cloudwatch-agent.sh [config-path]
#
# What this script can and cannot prove. It verifies the agent is installed, configured, running,
# and enabled at boot. It CANNOT verify metrics are reaching CloudWatch: that needs
# cloudwatch:GetMetricStatistics, which the instance role deliberately does not carry (the agent
# only needs PutMetricData). So an agent whose role lacks CloudWatchAgentServerPolicy will pass
# every check here and still publish nothing. Confirm from an operator shell with the
# get-metric-statistics call in docs/OPERATIONS_RUNBOOK.md, never from this script's exit code.

CONFIG_SRC="${1:-/opt/durion/alpha/cloudwatch-agent-config.json}"
AGENT_DIR=/opt/aws/amazon-cloudwatch-agent
AGENT_CTL="${AGENT_DIR}/bin/amazon-cloudwatch-agent-ctl"
CONFIG_DEST="${AGENT_DIR}/etc/durion-alpha.json"
SERVICE=amazon-cloudwatch-agent

die() { echo "$*" >&2; exit 1; }

[[ "${EUID}" -eq 0 ]] || die "Must run as root: dnf, ${AGENT_DIR} and systemctl all need it."
[[ -f "${CONFIG_SRC}" ]] || die "Config not found: ${CONFIG_SRC}"

# Validate before touching the live config. fetch-config replaces the running configuration and
# restarts the agent, so a malformed file caught here is a no-op, while the same file caught there
# leaves the agent stopped with its previous config already gone.
python3 -m json.tool "${CONFIG_SRC}" > /dev/null \
  || die "Config is not valid JSON: ${CONFIG_SRC}"

if ! rpm -q "${SERVICE}" > /dev/null 2>&1; then
  echo "Installing ${SERVICE} from the Amazon Linux repos."
  # AL2023 ships the agent, so no S3 download and no signature handling.
  dnf install -y "${SERVICE}"
else
  echo "${SERVICE} already installed: $(rpm -q "${SERVICE}")"
fi

# A package layout change would otherwise surface as a bare "No such file or directory".
[[ -x "${AGENT_CTL}" ]] || die "Agent control binary missing after install: ${AGENT_CTL}"

install -D -m 0644 "${CONFIG_SRC}" "${CONFIG_DEST}"

# -s starts the agent, and restarts it if already running so a changed config takes effect.
"${AGENT_CTL}" -a fetch-config -m ec2 -s -c "file:${CONFIG_DEST}" \
  || die "fetch-config failed. The agent may be stopped or running the previous config; re-run once ${CONFIG_SRC} is valid."

systemctl enable "${SERVICE}" > /dev/null 2>&1 || true

# Assert rather than print. The point of this script is that the box is being watched, so every
# condition that would leave it unwatched has to fail the run.
STATUS_JSON="$("${AGENT_CTL}" -a status)"
echo "${STATUS_JSON}"

grep -q '"status": *"running"' <<< "${STATUS_JSON}" \
  || die "Agent is not running after fetch-config."
grep -q '"configstatus": *"configured"' <<< "${STATUS_JSON}" \
  || die "Agent is running but reports no configuration."
systemctl is-enabled --quiet "${SERVICE}" \
  || die "Agent is running but not enabled at boot; it would not survive a reboot."

echo
echo "Agent running, configured, and enabled at boot."
echo "Publishing disk used_percent and free on /, plus mem_used_percent, at 60s to the CWAgent namespace."
echo "Metrics take a few minutes to appear. Verify from an operator shell — this script cannot."

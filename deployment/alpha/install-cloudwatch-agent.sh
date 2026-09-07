#!/usr/bin/env bash
set -euo pipefail

# Install and configure the CloudWatch agent on the alpha host (#1862 follow-up).
#
# Alpha filled its root disk and wedged: every deploy failed on `no space left on device`, and
# because a full root filesystem also stops the SSM agent from writing its output, there was no
# remote shell left to diagnose it with. Nothing alarmed, because no host metrics were published
# at all — EC2 publishes CPU and network from the hypervisor but never disk or memory, which need
# an agent inside the instance.
#
# This publishes the two that would have caught it, and nothing else: disk on / (where Docker's
# data root lives) and memory. Every metric is billed monthly, so the set stays deliberately small.
#
# Idempotent: safe to re-run to pick up a changed config. Run it after any edit to
# cloudwatch-agent-config.json.
#
# Usage (on the box):
#   bash /opt/durion/alpha/scripts/install-cloudwatch-agent.sh [config-path]
#
# Prerequisite, granted once and not by this script: the instance role needs
# CloudWatchAgentServerPolicy. Without it the agent starts, looks healthy, and silently publishes
# nothing — which is the failure mode this whole exercise exists to remove, so the check below
# treats a missing metric as an error rather than letting it pass quietly.

CONFIG_SRC="${1:-/opt/durion/alpha/cloudwatch-agent-config.json}"
AGENT_CTL=/opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl
CONFIG_DEST=/opt/aws/amazon-cloudwatch-agent/etc/durion-alpha.json

if [[ ! -f "${CONFIG_SRC}" ]]; then
  echo "Config not found: ${CONFIG_SRC}" >&2
  exit 1
fi

if ! rpm -q amazon-cloudwatch-agent >/dev/null 2>&1; then
  echo "Installing amazon-cloudwatch-agent from the Amazon Linux repos."
  # AL2023 ships the agent, so no S3 download and no signature dance.
  dnf install -y amazon-cloudwatch-agent
else
  echo "amazon-cloudwatch-agent already installed: $(rpm -q amazon-cloudwatch-agent)"
fi

install -m 0644 "${CONFIG_SRC}" "${CONFIG_DEST}"

# -s starts the agent (and restarts it if already running, picking up the new config).
"${AGENT_CTL}" -a fetch-config -m ec2 -s -c "file:${CONFIG_DEST}"

systemctl enable amazon-cloudwatch-agent >/dev/null 2>&1 || true

echo
echo "Agent status:"
"${AGENT_CTL}" -a status

echo
echo "Configured metrics: disk used_percent and free on /, plus mem_used_percent, at 60s."
echo "They take a few minutes to appear in the CWAgent namespace."

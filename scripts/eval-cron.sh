#!/usr/bin/env bash
# Scheduled MCP retrieval-quality regression gate (#783 close item 2, option C).
#
# Runs scripts/eval_live.py against the live alpha stack on a schedule and fails (non-zero exit +
# stderr banner) when hit@5 / MRR / recall@k drop below their floors or a forbidden tool/doc leaks.
# Meant to run from cron ON THE ALPHA HOST, which is the only place with network access to alpha's
# localhost pgvector + Ollama. GitHub-hosted runners cannot reach them, so this is the JVM-free,
# host-side equivalent of a CI gate.
#
# Design: quiet on success (full output to a timestamped log, nothing to the terminal), loud on
# failure (banner + tail to stderr, non-zero exit). With `MAILTO=you@example.com` in the crontab,
# cron then emails ONLY on failure. Set EVAL_ALERT_WEBHOOK to also POST a failure summary (Slack-style
# {"text": ...}).
#
# Config (all optional; sensible defaults):
#   REPO_DIR                 repo checkout to run from      (default: this script's repo root)
#   ENV_FILE                 .env with DB creds             (default: /opt/durion/alpha/.env)
#   OLLAMA_EMBEDDING_BASE_URL embedding backend             (default: http://localhost:11434)
#   EVAL_REF                 git ref to fetch+checkout first (default: run whatever is checked out)
#   EVAL_MIN_HIT5 / EVAL_MIN_MRR / EVAL_MIN_RECALL  threshold overrides (else eval_live.py defaults)
#   EVAL_ALERT_WEBHOOK       optional webhook URL for failure alerts
#   LOG_DIR                  log destination                (default: $REPO_DIR/pos-mcp-server/target/eval/cron)
#   LOG_RETENTION            logs to keep                   (default: 30)
#
# Install (example — 06:30 UTC daily, emails on failure):
#   crontab -e
#   MAILTO=you@example.com
#   30 6 * * * ENV_FILE=/opt/durion/alpha/.env /home/ec2-user/durion-eval/scripts/eval-cron.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="${REPO_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"
ENV_FILE="${ENV_FILE:-/opt/durion/alpha/.env}"
export ENV_FILE
export OLLAMA_EMBEDDING_BASE_URL="${OLLAMA_EMBEDDING_BASE_URL:-http://localhost:11434}"
LOG_DIR="${LOG_DIR:-$REPO_DIR/pos-mcp-server/target/eval/cron}"
LOG_RETENTION="${LOG_RETENTION:-30}"

mkdir -p "$LOG_DIR"
# Timestamp from the shell (eval_live.py can't call time itself); date here is fine, this is a script.
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
LOG_FILE="$LOG_DIR/eval-$STAMP.log"

cd "$REPO_DIR"

# Optionally pin to a ref (e.g. main after the fix merges) so the gate tracks a known baseline.
if [[ -n "${EVAL_REF:-}" ]]; then
  git fetch --quiet origin "$EVAL_REF" && git checkout --quiet -B "$EVAL_REF" "origin/$EVAL_REF"
fi

fail() {
  local msg="$1"
  {
    echo "=================================================================="
    echo " MCP EVAL REGRESSION — FAILED  ($STAMP)"
    echo " host=$(hostname)  repo=$REPO_DIR  ref=${EVAL_REF:-<checked-out>}"
    echo " $msg"
    echo " log: $LOG_FILE"
    echo "------------------------------------------------------------------"
    tail -n 25 "$LOG_FILE" 2>/dev/null || true
    echo "=================================================================="
  } >&2
  if [[ -n "${EVAL_ALERT_WEBHOOK:-}" ]]; then
    local summary
    summary="$(tr -d '\r' <"$LOG_FILE" | tail -n 6 | sed 's/"/\\"/g' | tr '\n' ' ')"
    curl -fsS -X POST -H 'Content-Type: application/json' \
      --data "{\"text\":\"MCP eval regression on $(hostname): $msg — $summary\"}" \
      "$EVAL_ALERT_WEBHOOK" >/dev/null 2>&1 || echo "(webhook alert failed)" >&2
  fi
}

# Run the gate. eval_live.py exits non-zero on any threshold breach or forbidden leak.
set +e
python3 scripts/eval_live.py >"$LOG_FILE" 2>&1
STATUS=$?
set -e

# Prune old logs (keep newest LOG_RETENTION).
ls -1t "$LOG_DIR"/eval-*.log 2>/dev/null | tail -n +"$((LOG_RETENTION + 1))" | xargs -r rm -f

if [[ $STATUS -ne 0 ]]; then
  fail "eval_live.py exited $STATUS"
  exit "$STATUS"
fi
# Success: stay quiet so cron doesn't email. Log only to file.
exit 0

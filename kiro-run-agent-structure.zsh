#!/usr/bin/env zsh
emulate -L zsh
print -u2 "=== SCRIPT START ==="
set -euo pipefail

# ---- Config ----
TASK_FILE=".kiro/specs/agent-structure/tasks.md"   # <-- set this to the real filename
HANDOFF_FILE=".kiro/HANDOFF.md"
LOG_DIR=".kiro/run-logs"
DATE_TAG="$(date +%Y%m%d-%H%M%S)"
LOG_FILE="${LOG_DIR}/agent-structure-${DATE_TAG}.log"

KIRO_CMD="${KIRO_CMD:-kiro-cli}"
MAX_STEPS="${MAX_STEPS:-1}"

# Optional: specify an agent name configured in Kiro
# Example: export KIRO_AGENT="moquiDeveloper-agent"
KIRO_AGENT="${KIRO_AGENT:-}"

mkdir -p "${LOG_DIR}"

if [[ ! -f "${TASK_FILE}" ]]; then
  print -u2 "ERROR: Task file not found: ${TASK_FILE}"
  exit 2
fi

if ! command -v "${KIRO_CMD}" >/dev/null 2>&1; then
  print -u2 "ERROR: '${KIRO_CMD}' not found in PATH."
  exit 3
fi

# Ensure handoff exists
if [[ ! -f "${HANDOFF_FILE}" ]]; then
  cat > "${HANDOFF_FILE}" <<EOF
# Kiro Handoff

## Goal
Execute the next unchecked task from: ${TASK_FILE}

## Current Status
- Not started.

## Next Task
- (Kiro: choose the NEXT unchecked checkbox from the task file and do only that.)

## How to Test
- (Fill in test commands here once known.)

## Known Issues / Notes
- (Kiro: update as you learn.)
EOF
fi

PROMPT=$'You are operating on this repository.\n\n'\
$'Files of record:\n'\
$'- Task list: '"${TASK_FILE}"$'\n'\
$'- Handoff: '"${HANDOFF_FILE}"$'\n\n'\
$'Instructions (contractual):\n'\
$'1) Read BOTH files.\n'\
$'2) Execute ONLY the next unchecked task from the task list (exactly one task).\n'\
$'3) Make minimal, focused edits needed to complete that single task.\n'\
$'4) Update the task list (check it off) and update the handoff with:\n'\
$'   - what you changed\n'\
$'   - commands run + results\n'\
$'   - the next task to do\n'\
$'5) If the handoff contains test commands, run them. Otherwise, skip tests.\n'\
$'6) Stop after completing the single task.\n\n'\
$'Important:\n'\
$'- Do NOT refactor unrelated areas.\n'\
$'- Prefer updating the handoff and stopping over reading extra files.\n'

integer step=1
while (( step <= MAX_STEPS )); do
  print "==> Kiro step ${step}/${MAX_STEPS} (logging to ${LOG_FILE})"

  # Build args (zsh-safe)
  args=()
  if [[ -n "${KIRO_AGENT}" ]]; then
    args+=( --agent "${KIRO_AGENT}" )
  fi

  # Terminal chat mode
  "${KIRO_CMD}" chat "${args[@]}" --no-interactive --trust-all-tools "${PROMPT}" 2>&1 | tee -a "${LOG_FILE}"

  # Stop early if no unchecked tasks remain
  if ! grep -Eq '^\s*[-*]\s+\[\s\]\s+' "${TASK_FILE}"; then
    print "==> No unchecked tasks detected in ${TASK_FILE}. Stopping."
    break
  fi

  (( step++ ))
done

print "==> Done. Log: ${LOG_FILE}"

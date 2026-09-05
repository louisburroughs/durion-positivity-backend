#!/bin/bash
# SessionStart hook for Claude Code on the web: make sure JDK 25 is present.
#
# The real work lives in scripts/setup-jdk25.sh so the same script can be
# pasted into the cloud environment's Setup script field, where it runs once
# and gets baked into the cached filesystem snapshot. See that file's header.
set -euo pipefail

# Web sessions only — local developers use SDKMAN per .sdkmanrc.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

exec "$(dirname "$0")/../../scripts/setup-jdk25.sh"

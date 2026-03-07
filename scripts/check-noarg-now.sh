#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

PATTERN='\b(Instant\.now|LocalDateTime\.now)\(\s*\)'

matches=$(rg -n --no-heading "$PATTERN" --glob "**/src/main/**" --glob "**/src/test/**" . || true)

if [[ -n "$matches" ]]; then
  echo "ERROR: Found forbidden no-arg time calls in src/main or src/test:"
  echo "$matches"
  exit 1
fi

echo "PASS: No forbidden no-arg Instant.now()/LocalDateTime.now() calls in src/main or src/test."

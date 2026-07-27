#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

PATTERN='\b(Instant\.now|LocalDateTime\.now|LocalDate\.now|Year\.now)\(\s*\)'

matches=$(rg -n --no-heading "$PATTERN" --glob "**/src/main/**" . || true)

if [[ -n "$matches" ]]; then
  echo "ERROR: Found forbidden no-arg time calls in src/main:"
  echo "$matches"
  exit 1
fi

echo "PASS: No forbidden no-arg Instant/LocalDateTime/LocalDate/Year now() calls in src/main."

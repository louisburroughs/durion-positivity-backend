#!/usr/bin/env bash
# Run the live-gated MCP eval ITs read-only against the alpha stack.
#
# Prereqs (on a host with line-of-sight to the alpha DB + local Ollama embedding container):
#   - JDK 25 on PATH (repo is Java 25; ./mvnw fetches Maven)
#   - alpha Postgres reachable  (default: localhost:5432, published on the alpha host)
#   - local Ollama embedding    (default: http://localhost:11434, the nomic-embed-text container)
#
# The ITs boot lean + read-only: Flyway is disabled (NO migrations applied to the DB), no web
# server, no Eureka, no permission registration — only the DB + embedding model are contacted.
#
# Usage:
#   POS_MCP_DB_PASSWORD=... scripts/run-live-eval.sh                 # both ITs
#   POS_MCP_DB_PASSWORD=... scripts/run-live-eval.sh BaselineCaptureIT
#   POS_MCP_DB_HOST=db POS_MCP_DB_USER=pos_mcp POS_MCP_DB_PASSWORD=... scripts/run-live-eval.sh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

export POS_MCP_DB_HOST="${POS_MCP_DB_HOST:-localhost}"
export POS_MCP_DB_PORT="${POS_MCP_DB_PORT:-5432}"
export POS_MCP_DB_NAME="${POS_MCP_DB_NAME:-pos_mcp}"
export POS_MCP_DB_USER="${POS_MCP_DB_USER:-pos_mcp}"
export OLLAMA_EMBEDDING_BASE_URL="${OLLAMA_EMBEDDING_BASE_URL:-http://localhost:11434}"
: "${POS_MCP_DB_PASSWORD:?set POS_MCP_DB_PASSWORD to the alpha pos_mcp DB password}"

TESTS="${1:-OpenApiToolPermissionGatingIT,BaselineCaptureIT}"

echo "DB        = ${POS_MCP_DB_USER}@${POS_MCP_DB_HOST}:${POS_MCP_DB_PORT}/${POS_MCP_DB_NAME}"
echo "Embedding = ${OLLAMA_EMBEDDING_BASE_URL}"
echo "Tests     = ${TESTS}"
echo

./mvnw -pl pos-mcp-server test \
  -Dtest="${TESTS}" \
  -Dmcp.eval.live=true \
  -Dspring.profiles.active=alpha \
  -Dsurefire.failIfNoSpecifiedTests=false

echo
echo "=== baseline-tool-selection.json (if BaselineCaptureIT ran) ==="
cat pos-mcp-server/target/eval/baseline-tool-selection.json 2>/dev/null || echo "  (not produced)"

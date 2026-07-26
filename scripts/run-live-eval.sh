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
# Credentials are read from ./.env automatically (SPRING_DATASOURCE_USERNAME/PASSWORD, the app's
# DB user; falls back to POSTGRES_USER/PASSWORD). Override any POS_MCP_DB_* var explicitly if needed.
#
# Usage:
#   scripts/run-live-eval.sh                        # both ITs, creds from .env
#   scripts/run-live-eval.sh BaselineCaptureIT      # one IT
#   POS_MCP_DB_PASSWORD=... scripts/run-live-eval.sh # override a cred explicitly
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# Pull DB creds from .env if present. pos-mcp-server connects with the *application* user
# (SPRING_DATASOURCE_USERNAME/PASSWORD -> POS_MCP_DB_USER/PASSWORD in docker-compose), NOT the
# Timescale superuser (POSTGRES_USER/PASSWORD). Spring itself does not read .env, so map it here.
# Precedence: explicit POS_MCP_DB_* env > .env SPRING_DATASOURCE_* > .env POSTGRES_* fallback.
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env}"
env_get() { [ -f "$ENV_FILE" ] && grep -E "^$1=" "$ENV_FILE" | tail -1 | cut -d= -f2- | sed -e 's/^"//' -e 's/"$//'; }

export POS_MCP_DB_HOST="${POS_MCP_DB_HOST:-localhost}"
export POS_MCP_DB_PORT="${POS_MCP_DB_PORT:-5432}"
export POS_MCP_DB_NAME="${POS_MCP_DB_NAME:-pos_mcp}"
export POS_MCP_DB_USER="${POS_MCP_DB_USER:-$(env_get SPRING_DATASOURCE_USERNAME)}"
export POS_MCP_DB_USER="${POS_MCP_DB_USER:-$(env_get POSTGRES_USER)}"
export POS_MCP_DB_PASSWORD="${POS_MCP_DB_PASSWORD:-$(env_get SPRING_DATASOURCE_PASSWORD)}"
export POS_MCP_DB_PASSWORD="${POS_MCP_DB_PASSWORD:-$(env_get POSTGRES_PASSWORD)}"
export OLLAMA_EMBEDDING_BASE_URL="${OLLAMA_EMBEDDING_BASE_URL:-http://localhost:11434}"

: "${POS_MCP_DB_USER:?could not resolve DB user (set POS_MCP_DB_USER or SPRING_DATASOURCE_USERNAME in .env)}"
: "${POS_MCP_DB_PASSWORD:?could not resolve DB password (set POS_MCP_DB_PASSWORD or SPRING_DATASOURCE_PASSWORD in .env)}"

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

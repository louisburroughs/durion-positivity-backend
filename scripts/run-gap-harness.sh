#!/usr/bin/env bash
# Run the RAG corpus gap-discovery harness (#1125) against a live stack.
#
# The harness asks realistic questions through the MCP chat API, reproduces the retrieval that fed
# the prompt (reusing eval_live.py's DB path), grades answers with a source-grounded judge,
# classifies every failure into the four-way taxonomy, and emits a gap report + dense-vs-hybrid
# flip-threshold evidence.
#
# Prereqs (a host with line-of-sight to the stack — see scripts/run-live-eval.sh for the DB/embed setup):
#   - python3 + pg8000            (pip install --user pg8000)
#   - MCP chat API reachable      (GAP_HARNESS_BASE_URL, default http://localhost:8080/mcp-server —
#                                  the gateway route; a bare http://localhost:8080 404s, since the
#                                  chat path lives behind the /mcp-server gateway route)
#   - alpha Postgres reachable    (POS_MCP_DB_* / .env, for retrieved-context capture)
#   - local Ollama embedding      (OLLAMA_EMBEDDING_BASE_URL, nomic-embed-text)
#   - a judge chat model          (--judge ollama|openai; ungraded refusal-only pass with --judge none)
#   - bearer token(s)             (POS_MCP_TOKEN_<ROLE> env, or --token for a single actor)
#
# Usage:
#   POS_MCP_TOKEN_ROLE_ADMIN=... POS_MCP_TOKEN_ROLE_USER=... \
#     scripts/run-gap-harness.sh --judge ollama --judge-model llama3.1 --recall-at-k 0.85
#   scripts/run-gap-harness.sh --token "$JWT" --include-fixtures     # single actor, add fixture questions
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# The chat endpoint lives behind the gateway's /mcp-server route (Path=/mcp-server/** + StripPrefix=1);
# the gateway validates the JWT and injects the X-Authorities headers the service trusts. A bare
# http://localhost:8080 has no such route and 404s — the CLI preflight now catches that early.
BASE_URL="${GAP_HARNESS_BASE_URL:-http://localhost:8080/mcp-server}"
OUT="${GAP_HARNESS_OUT:-pos-mcp-server/target/gap-harness}"

# Pass-through: everything after the known --base-url/--out defaults goes straight to the CLI, so
# --judge, --judge-model, --token, --include-fixtures, --usage, --limit, --recall-at-k all work.
exec python3 scripts/rag_gap_harness.py run --base-url "$BASE_URL" --out "$OUT" "$@"

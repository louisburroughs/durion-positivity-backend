#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  ./scripts/redeploy-backend-tag.sh <backend-tag> [service...]

Examples:
  ./scripts/redeploy-backend-tag.sh sha-a20f156 pos-vehicle-inventory
  ./scripts/redeploy-backend-tag.sh a20f156 pos-security-service pos-api-gateway
  ./scripts/redeploy-backend-tag.sh sha-a20f156

Behavior:
  1. Updates BACKEND_TAG in /opt/durion/alpha/.env
  2. Runs docker compose pull
  3. Runs docker compose up -d --force-recreate
  4. If services are provided, tails logs for those services
     If no services are provided, prints docker compose ps

Environment overrides (optional):
  ALPHA_ROOT      Default: /opt/durion/alpha
  BACKEND_DIR     Default: ${ALPHA_ROOT}/backend
  ENV_FILE        Default: ${ALPHA_ROOT}/.env
  PROD_OVERRIDE   Default: ${ALPHA_ROOT}/docker-compose.prod.yml
  LOG_TAIL        Default: 100
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" || $# -lt 1 ]]; then
  usage
  exit 1
fi

RAW_TAG="$1"
shift

if [[ "$RAW_TAG" == sha-* ]]; then
  BACKEND_TAG="$RAW_TAG"
else
  BACKEND_TAG="sha-${RAW_TAG}"
fi

ALPHA_ROOT="${ALPHA_ROOT:-/opt/durion/alpha}"
BACKEND_DIR="${BACKEND_DIR:-${ALPHA_ROOT}/backend}"
ENV_FILE="${ENV_FILE:-${ALPHA_ROOT}/.env}"
PROD_OVERRIDE="${PROD_OVERRIDE:-${ALPHA_ROOT}/docker-compose.prod.yml}"
LOG_TAIL="${LOG_TAIL:-100}"

if [[ ! -d "$BACKEND_DIR" ]]; then
  echo "ERROR: backend directory not found: $BACKEND_DIR" >&2
  exit 1
fi

if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERROR: env file not found: $ENV_FILE" >&2
  exit 1
fi

if [[ ! -f "$PROD_OVERRIDE" ]]; then
  echo "ERROR: compose override file not found: $PROD_OVERRIDE" >&2
  exit 1
fi

if grep -q '^BACKEND_TAG=' "$ENV_FILE"; then
  sed -i "s/^BACKEND_TAG=.*/BACKEND_TAG=${BACKEND_TAG}/" "$ENV_FILE"
else
  printf '\nBACKEND_TAG=%s\n' "$BACKEND_TAG" >> "$ENV_FILE"
fi

echo "Updated BACKEND_TAG=${BACKEND_TAG} in ${ENV_FILE}"

cd "$BACKEND_DIR"

COMPOSE_ARGS=(
  -f docker-compose.yml
  -f "$PROD_OVERRIDE"
  --env-file "$ENV_FILE"
)

desired_postgres_image="$(
  docker compose "${COMPOSE_ARGS[@]}" config \
    | sed -n '/^  postgres:/,/^[^ ]/p' \
    | awk '/image:/ {print $2; exit}'
)"
current_postgres_container_id="$(docker compose "${COMPOSE_ARGS[@]}" ps -q postgres 2>/dev/null || true)"
current_postgres_image=""

if [[ -n "$current_postgres_container_id" ]]; then
  current_postgres_image="$(docker inspect -f '{{.Config.Image}}' "$current_postgres_container_id" 2>/dev/null || true)"
fi

if [[ -n "$desired_postgres_image" && "$current_postgres_image" != "$desired_postgres_image" ]]; then
  echo "Reconciling postgres image: current='${current_postgres_image:-<none>}' desired='${desired_postgres_image}'"
  docker compose "${COMPOSE_ARGS[@]}" pull postgres
  docker compose "${COMPOSE_ARGS[@]}" up -d --force-recreate postgres
fi

if [[ $# -gt 0 ]]; then
  SERVICES=("$@")
  echo "Pulling services: ${SERVICES[*]}"
  docker compose "${COMPOSE_ARGS[@]}" pull "${SERVICES[@]}"

  echo "Recreating services: ${SERVICES[*]}"
  docker compose "${COMPOSE_ARGS[@]}" up -d --force-recreate "${SERVICES[@]}"

  echo "Recent logs:"
  docker compose "${COMPOSE_ARGS[@]}" logs --tail="$LOG_TAIL" "${SERVICES[@]}"
else
  echo "Pulling all services"
  docker compose "${COMPOSE_ARGS[@]}" pull

  echo "Recreating all services"
  docker compose "${COMPOSE_ARGS[@]}" up -d --force-recreate

  echo "Current service status:"
  docker compose "${COMPOSE_ARGS[@]}" ps
fi

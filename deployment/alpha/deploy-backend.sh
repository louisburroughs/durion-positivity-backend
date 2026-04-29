#!/usr/bin/env bash
set -euo pipefail

GITHUB_SHA="${1:?GITHUB_SHA argument required}"
IMAGE_TAG="sha-${GITHUB_SHA::7}"

ALPHA_ROOT="${ALPHA_ROOT:-/opt/durion/alpha}"
BACKEND_DIR="${BACKEND_DIR:-${ALPHA_ROOT}/backend}"
ENV_FILE="${ENV_FILE:-${ALPHA_ROOT}/.env}"
PROD_OVERRIDE="${PROD_OVERRIDE:-${ALPHA_ROOT}/docker-compose.prod.yml}"
DOCKER_PRUNE_INTERVAL_HOURS="${DOCKER_PRUNE_INTERVAL_HOURS:-24}"
DOCKER_PRUNE_STATE_FILE="${DOCKER_PRUNE_STATE_FILE:-${ALPHA_ROOT}/.last-docker-prune-at}"

env_single_quote() {
  printf "'%s'" "${1//\'/\'\"\'\"\'}"
}

should_run_docker_prune() {
  if ! [[ "${DOCKER_PRUNE_INTERVAL_HOURS}" =~ ^[0-9]+$ ]]; then
    echo "Skipping Docker prune: DOCKER_PRUNE_INTERVAL_HOURS must be an integer, got '${DOCKER_PRUNE_INTERVAL_HOURS}'." >&2
    return 1
  fi

  if [[ "${DOCKER_PRUNE_INTERVAL_HOURS}" -le 0 ]]; then
    return 0
  fi

  if [[ ! -f "${DOCKER_PRUNE_STATE_FILE}" ]]; then
    return 0
  fi

  local now_epoch last_prune_epoch min_interval_seconds
  now_epoch="$(date +%s)"
  last_prune_epoch="$(cat "${DOCKER_PRUNE_STATE_FILE}" 2>/dev/null || echo 0)"
  min_interval_seconds="$((DOCKER_PRUNE_INTERVAL_HOURS * 3600))"

  [[ "$((now_epoch - last_prune_epoch))" -ge "${min_interval_seconds}" ]]
}

run_periodic_docker_prune() {
  if ! should_run_docker_prune; then
    echo "Skipping Docker prune: last cleanup is newer than ${DOCKER_PRUNE_INTERVAL_HOURS}h."
    return 0
  fi

  echo "Docker disk usage before prune:"
  docker system df || true

  if docker system prune -af; then
    date +%s > "${DOCKER_PRUNE_STATE_FILE}"
    echo "Docker disk usage after prune:"
    docker system df || true
    return 0
  fi

  echo "Warning: docker system prune failed; deployment succeeded but cleanup did not." >&2
  return 0
}

OBSERVABILITY_SERVICES=(
  jaeger
  prometheus
  otel-collector
  grafana
)

BACKEND_SERVICES=(
  eureka-server
  pos-accounting
  pos-api-gateway
  pos-catalog
  pos-customer
  pos-event-receiver
  pos-image
  pos-inventory
  pos-invoice
  pos-location
  pos-mcp-server
  pos-people
  pos-price
  pos-security-service
  pos-shop-manager
  pos-vehicle-inventory
  pos-workorder
)

if grep -q '^BACKEND_TAG=' "${ENV_FILE}"; then
  sed -i "s/^BACKEND_TAG=.*/BACKEND_TAG=${IMAGE_TAG}/" "${ENV_FILE}"
else
  printf '\nBACKEND_TAG=%s\n' "${IMAGE_TAG}" >> "${ENV_FILE}"
fi

if [[ -z "${SECURITY_SEED_ADMIN_PASSWORD_HASH:-}" ]]; then
  echo "SECURITY_SEED_ADMIN_PASSWORD_HASH is required."
  exit 1
fi
if [[ ! "${SECURITY_SEED_ADMIN_PASSWORD_HASH}" =~ ^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$ ]]; then
  echo "SECURITY_SEED_ADMIN_PASSWORD_HASH must be a valid BCrypt hash (expected prefix \$2a\$, \$2b\$, or \$2y\$)." >&2
  exit 1
fi

QUOTED_SECURITY_SEED_ADMIN_PASSWORD_HASH="$(env_single_quote "${SECURITY_SEED_ADMIN_PASSWORD_HASH}")"
if grep -q '^SECURITY_SEED_ADMIN_PASSWORD_HASH=' "${ENV_FILE}"; then
  sed -i "s|^SECURITY_SEED_ADMIN_PASSWORD_HASH=.*|SECURITY_SEED_ADMIN_PASSWORD_HASH=${QUOTED_SECURITY_SEED_ADMIN_PASSWORD_HASH}|" "${ENV_FILE}"
else
  printf 'SECURITY_SEED_ADMIN_PASSWORD_HASH=%s\n' "${QUOTED_SECURITY_SEED_ADMIN_PASSWORD_HASH}" >> "${ENV_FILE}"
fi

ECR_REGISTRY="$(aws sts get-caller-identity --query Account --output text).dkr.ecr.us-east-1.amazonaws.com"
if grep -q '^ECR_REGISTRY=' "${ENV_FILE}"; then
  sed -i "s|^ECR_REGISTRY=.*|ECR_REGISTRY=${ECR_REGISTRY}|" "${ENV_FILE}"
else
  printf 'ECR_REGISTRY=%s\n' "${ECR_REGISTRY}" >> "${ENV_FILE}"
fi

aws ecr get-login-password --region us-east-1 \
  | docker login --username AWS --password-stdin "${ECR_REGISTRY}"

cd "${BACKEND_DIR}"

COMPOSE_ARGS=(
  -f docker-compose.yml
  -f "${PROD_OVERRIDE}"
  --env-file "${ENV_FILE}"
)

DESIRED_POSTGRES_IMAGE="$(
  docker compose "${COMPOSE_ARGS[@]}" config \
    | sed -n '/^  postgres:/,/^[^ ]/p' \
    | awk '/image:/ {print $2; exit}'
)"
CURRENT_POSTGRES_CONTAINER_ID="$(docker compose "${COMPOSE_ARGS[@]}" ps -q postgres 2>/dev/null || true)"
CURRENT_POSTGRES_IMAGE=""
if [[ -n "${CURRENT_POSTGRES_CONTAINER_ID}" ]]; then
  CURRENT_POSTGRES_IMAGE="$(docker inspect -f '{{.Config.Image}}' "${CURRENT_POSTGRES_CONTAINER_ID}" 2>/dev/null || true)"
fi
if [[ -n "${DESIRED_POSTGRES_IMAGE}" && "${CURRENT_POSTGRES_IMAGE}" != "${DESIRED_POSTGRES_IMAGE}" ]]; then
  echo "Reconciling postgres image: current='${CURRENT_POSTGRES_IMAGE:-<none>}' desired='${DESIRED_POSTGRES_IMAGE}'"
  docker compose "${COMPOSE_ARGS[@]}" pull postgres
  docker compose "${COMPOSE_ARGS[@]}" up -d --force-recreate postgres
fi

echo "Pulling observability services: ${OBSERVABILITY_SERVICES[*]}"
docker compose "${COMPOSE_ARGS[@]}" pull --quiet "${OBSERVABILITY_SERVICES[@]}"

echo "Starting observability services: ${OBSERVABILITY_SERVICES[*]}"
docker compose "${COMPOSE_ARGS[@]}" up -d --force-recreate "${OBSERVABILITY_SERVICES[@]}"

echo "Pulling backend services: ${BACKEND_SERVICES[*]}"
docker compose "${COMPOSE_ARGS[@]}" pull --quiet "${BACKEND_SERVICES[@]}"

echo "Starting backend services: ${BACKEND_SERVICES[*]}"
docker compose "${COMPOSE_ARGS[@]}" up -d --force-recreate "${BACKEND_SERVICES[@]}"

docker compose "${COMPOSE_ARGS[@]}" ps
run_periodic_docker_prune

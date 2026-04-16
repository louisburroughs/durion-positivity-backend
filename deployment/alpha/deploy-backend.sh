#!/usr/bin/env bash
set -euo pipefail

GITHUB_SHA="${1:?GITHUB_SHA argument required}"
IMAGE_TAG="sha-${GITHUB_SHA::7}"

ALPHA_ROOT="${ALPHA_ROOT:-/opt/durion/alpha}"
BACKEND_DIR="${BACKEND_DIR:-${ALPHA_ROOT}/backend}"
ENV_FILE="${ENV_FILE:-${ALPHA_ROOT}/.env}"
PROD_OVERRIDE="${PROD_OVERRIDE:-${ALPHA_ROOT}/docker-compose.prod.yml}"

env_single_quote() {
  printf "'%s'" "${1//\'/\'\"\'\"\'}"
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

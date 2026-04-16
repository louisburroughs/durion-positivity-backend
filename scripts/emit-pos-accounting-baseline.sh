#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Emit a scratch Postgres schema for pos-accounting using the current entity model.

This script:
1. Recreates a scratch database inside a running Docker Postgres container
2. Starts `pos-accounting` with Flyway disabled and Hibernate `ddl-auto=create`
3. Waits for core tables to appear
4. Stops the app and dumps the emitted schema to a local file

Usage:
  ./scripts/emit-pos-accounting-baseline.sh

Environment overrides:
  POSTGRES_CONTAINER            Docker container name (default: pos-accounting-baseline-pg)
  POSTGRES_USER                 Database user (default: postgres)
  POSTGRES_PASSWORD             Database password (default: postgres)
  HOST_DB_PORT                  Host port exposed by the container (default: 5432)
  SCRATCH_DB                    Scratch database name (default: pos_accounting_baseline_tmp)
  EMITTED_SCHEMA_OUTPUT         Host output file (default: /tmp/pos_accounting_baseline_emitted.sql)
  BOOTSTRAP_LOG                 App startup log file (default: /tmp/pos_accounting_baseline_bootstrap.log)
  WAIT_TIMEOUT_SECONDS          How long to wait for schema creation (default: 180)

Example:
  POSTGRES_CONTAINER=pos-accounting-baseline-pg \
  ./scripts/emit-pos-accounting-baseline.sh
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
ACCOUNTING_DIR="${REPO_ROOT}/pos-accounting"

POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-pos-accounting-baseline-pg}"
POSTGRES_USER="${POSTGRES_USER:-postgres}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-postgres}"
HOST_DB_PORT="${HOST_DB_PORT:-5432}"
SCRATCH_DB="${SCRATCH_DB:-pos_accounting_baseline_tmp}"
EMITTED_SCHEMA_OUTPUT="${EMITTED_SCHEMA_OUTPUT:-/tmp/pos_accounting_baseline_emitted.sql}"
BOOTSTRAP_LOG="${BOOTSTRAP_LOG:-/tmp/pos_accounting_baseline_bootstrap.log}"
WAIT_TIMEOUT_SECONDS="${WAIT_TIMEOUT_SECONDS:-180}"

APP_PID=""

require_cmd() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "ERROR: required command not found: $cmd" >&2
    exit 1
  fi
}

cleanup() {
  if [[ -n "${APP_PID}" ]] && kill -0 "${APP_PID}" 2>/dev/null; then
    kill "${APP_PID}" 2>/dev/null || true
    wait "${APP_PID}" 2>/dev/null || true
  fi
}

trap cleanup EXIT INT TERM

require_cmd docker

if ! docker inspect "${POSTGRES_CONTAINER}" >/dev/null 2>&1; then
  echo "ERROR: Docker container not found: ${POSTGRES_CONTAINER}" >&2
  exit 1
fi

echo "Recreating scratch database ${SCRATCH_DB} in container ${POSTGRES_CONTAINER}..."
docker exec "${POSTGRES_CONTAINER}" env PGPASSWORD="${POSTGRES_PASSWORD}" \
  psql -U "${POSTGRES_USER}" -d postgres \
  -c "DROP DATABASE IF EXISTS ${SCRATCH_DB} WITH (FORCE);" >/dev/null

docker exec "${POSTGRES_CONTAINER}" env PGPASSWORD="${POSTGRES_PASSWORD}" \
  psql -U "${POSTGRES_USER}" -d postgres \
  -c "CREATE DATABASE ${SCRATCH_DB};" >/dev/null

spring_args=(
  "--spring.datasource.url=jdbc:postgresql://localhost:${HOST_DB_PORT}/${SCRATCH_DB}"
  "--spring.datasource.username=${POSTGRES_USER}"
  "--spring.datasource.password=${POSTGRES_PASSWORD}"
  "--spring.flyway.enabled=false"
  "--spring.jpa.hibernate.ddl-auto=create"
  "--eureka.client.enabled=false"
  "--eureka.client.fetch-registry=false"
  "--eureka.client.register-with-eureka=false"
  "--spring.cloud.discovery.enabled=false"
  "--pos.events.base-url=http://127.0.0.1:9"
  "--stripe.api-key=dummy"
  "--pos.accounting.credit-memo.revenue-account-id=00000000-0000-0000-0000-000000000001"
  "--pos.accounting.credit-memo.tax-payable-account-id=00000000-0000-0000-0000-000000000002"
  "--pos.accounting.credit-memo.ar-account-id=00000000-0000-0000-0000-000000000003"
)

printf -v joined_args '%s ' "${spring_args[@]}"
joined_args="${joined_args% }"

rm -f "${BOOTSTRAP_LOG}" "${EMITTED_SCHEMA_OUTPUT}"

echo "Starting pos-accounting with Flyway disabled..."
(
  cd "${ACCOUNTING_DIR}" &&
    ../mvnw spring-boot:run \
      -Dspring-boot.run.arguments="${joined_args}"
) >"${BOOTSTRAP_LOG}" 2>&1 &
APP_PID=$!

required_tables=(
  accounting_event
  accounting_audit_log
  idempotency_keys
  invoice_status_views
  posting_rule_version
  vendor_bill
  vendor_bill_line
)

required_tables_sql=""
for table_name in "${required_tables[@]}"; do
  if [[ -n "${required_tables_sql}" ]]; then
    required_tables_sql+=", "
  fi
  required_tables_sql+="'${table_name}'"
done

echo "Waiting for Hibernate to emit the accounting schema..."
deadline=$((SECONDS + WAIT_TIMEOUT_SECONDS))
schema_ready=false

while (( SECONDS < deadline )); do
  if ! kill -0 "${APP_PID}" 2>/dev/null; then
    echo "ERROR: pos-accounting exited before schema generation completed." >&2
    echo "Bootstrap log: ${BOOTSTRAP_LOG}" >&2
    tail -n 80 "${BOOTSTRAP_LOG}" >&2 || true
    exit 1
  fi

  table_count="$(
    docker exec "${POSTGRES_CONTAINER}" env PGPASSWORD="${POSTGRES_PASSWORD}" \
      psql -U "${POSTGRES_USER}" -d "${SCRATCH_DB}" -Atqc \
      "select count(*) from pg_tables where schemaname = 'public' and tablename in (${required_tables_sql});" \
      2>/dev/null || echo 0
  )"

  if [[ "${table_count}" =~ ^[0-9]+$ ]] && (( table_count == ${#required_tables[@]} )); then
    schema_ready=true
    break
  fi

  sleep 2
done

if [[ "${schema_ready}" != "true" ]]; then
  echo "ERROR: timed out waiting for scratch schema creation." >&2
  echo "Bootstrap log: ${BOOTSTRAP_LOG}" >&2
  tail -n 80 "${BOOTSTRAP_LOG}" >&2 || true
  exit 1
fi

echo "Schema detected; stopping pos-accounting and dumping schema..."
cleanup
APP_PID=""

docker exec "${POSTGRES_CONTAINER}" env PGPASSWORD="${POSTGRES_PASSWORD}" \
  pg_dump -U "${POSTGRES_USER}" \
  --schema-only \
  --no-owner \
  --no-privileges \
  "${SCRATCH_DB}" >"${EMITTED_SCHEMA_OUTPUT}"

echo
echo "Schema emitted successfully."
echo "Scratch DB: ${SCRATCH_DB}"
echo "Schema file: ${EMITTED_SCHEMA_OUTPUT}"
echo "Bootstrap log: ${BOOTSTRAP_LOG}"
echo
echo "Next useful command:"
echo "python3 \"${REPO_ROOT}/scripts/build-pos-accounting-baseline-from-dump.py\" \"${EMITTED_SCHEMA_OUTPUT}\""

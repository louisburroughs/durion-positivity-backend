#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/docker-compose.yml"
FORMAT="table"

usage() {
  cat <<'EOF'
Inventory Flyway-managed modules and summarize their baseline-reset characteristics.

Usage:
  ./scripts/inventory-flyway-modules.sh [--format table|tsv] [--compose <path>]

Examples:
  ./scripts/inventory-flyway-modules.sh
  ./scripts/inventory-flyway-modules.sh --format tsv
  ./scripts/inventory-flyway-modules.sh --compose /opt/durion/alpha/backend/docker-compose.yml
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --format)
      FORMAT="$2"
      shift 2
      ;;
    --compose)
      COMPOSE_FILE="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ ! -f "$COMPOSE_FILE" ]]; then
  echo "Compose file not found: $COMPOSE_FILE" >&2
  exit 1
fi

print_row() {
  local module="$1"
  local db_name="$2"
  local versioned="$3"
  local repeatable="$4"
  local first_version="$5"
  local last_version="$6"
  local h2_mode="$7"
  local custom_flyway="$8"
  local extensions="$9"
  local parity_count="${10}"

  if [[ "$FORMAT" == "tsv" ]]; then
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
      "$module" "$db_name" "$versioned" "$repeatable" "$first_version" "$last_version" \
      "$h2_mode" "$custom_flyway" "$extensions" "$parity_count"
  else
    printf '%-30s %-26s %4s %4s %-18s %-18s %-12s %-13s %-14s %4s\n' \
      "$module" "$db_name" "$versioned" "$repeatable" "$first_version" "$last_version" \
      "$h2_mode" "$custom_flyway" "$extensions" "$parity_count"
  fi
}

compose_service_block() {
  local service="$1"
  sed -n "/^  ${service}:$/,/^  [A-Za-z0-9_-]\\+:/p" "$COMPOSE_FILE" \
    | sed '$d' \
    | grep -v '^[[:space:]]*#' || true
}

detect_db_name() {
  local service="$1"
  local block
  block="$(compose_service_block "$service")"

  local mcp_db
  mcp_db="$(printf '%s\n' "$block" | sed -n 's/.*POS_MCP_DB_NAME:[[:space:]]*\([^[:space:]]*\).*/\1/p' | head -n 1)"
  if [[ -n "$mcp_db" ]]; then
    printf '%s' "$mcp_db"
    return
  fi

  local url
  url="$(printf '%s\n' "$block" | sed -n 's/.*SPRING_DATASOURCE_URL:[[:space:]]*jdbc:postgresql:\/\/[^/]*\/\([^[:space:]]*\).*/\1/p' | head -n 1)"
  if [[ -n "$url" ]]; then
    printf '%s' "$url"
    return
  fi

  printf '%s' "unknown"
}

detect_extensions() {
  local migration_dir="$1"
  local extensions=()

  if rg -qi 'create extension[^;]*timescaledb|timescaledb' "$migration_dir" 2>/dev/null; then
    extensions+=("timescaledb")
  fi

  if rg -qi 'create extension[^;]*vector|pgvector|vector_cosine_ops|vector\(' "$migration_dir" 2>/dev/null; then
    extensions+=("pgvector")
  fi

  if [[ ${#extensions[@]} -eq 0 ]]; then
    printf '%s' "none"
    return
  fi

  local joined
  joined="$(IFS=,; printf '%s' "${extensions[*]}")"
  printf '%s' "$joined"
}

count_parity_migrations() {
  local migration_dir="$1"
  find "$migration_dir" -maxdepth 1 -type f \
    \( -name '*parity*.sql' -o -name '*gap*.sql' -o -name '*baseline*.sql' -o -name '*remaining*.sql' -o -name '*tighten*.sql' -o -name '*align*.sql' \) \
    | wc -l | tr -d ' '
}

if [[ "$FORMAT" == "tsv" ]]; then
  print_row "module" "database" "V" "R" "first_version" "last_version" "h2_layout" "custom_flyway" "extensions" "parity"
else
  printf '%-30s %-26s %4s %4s %-18s %-18s %-12s %-13s %-14s %4s\n' \
    "MODULE" "DATABASE" "V" "R" "FIRST_VERSION" "LAST_VERSION" "H2_LAYOUT" "CUSTOM_FLYWAY" "EXTENSIONS" "PAR."
  printf '%s\n' "----------------------------------------------------------------------------------------------------------------------------------"
fi

shopt -s nullglob
for module_dir in "$ROOT_DIR"/pos-*; do
  [[ -d "$module_dir" ]] || continue

  migration_dir="$module_dir/src/main/resources/db/migration"
  [[ -d "$migration_dir" ]] || continue

  migration_files=("$migration_dir"/*.sql)
  [[ ${#migration_files[@]} -gt 0 ]] || continue

  module_name="$(basename "$module_dir")"
  versioned_count="$(find "$migration_dir" -maxdepth 1 -type f -name 'V*.sql' | wc -l | tr -d ' ')"
  repeatable_count="$(find "$migration_dir" -maxdepth 1 -type f -name 'R__*.sql' | wc -l | tr -d ' ')"

  first_version="$(find "$migration_dir" -maxdepth 1 -type f -name 'V*.sql' -printf '%f\n' | sort -V | head -n 1)"
  last_version="$(find "$migration_dir" -maxdepth 1 -type f -name 'V*.sql' -printf '%f\n' | sort -V | tail -n 1)"

  if [[ -d "$module_dir/src/main/resources/db/h2-migration" ]]; then
    h2_layout="split"
  elif [[ -d "$module_dir/src/main/resources/db/migration/h2" ]]; then
    h2_layout="nested"
  else
    h2_layout="none"
  fi

  if [[ -f "$module_dir/src/main/java/com/positivity/$(basename "$module_name" | sed 's/^pos-//; s/-//g')/internal/config/FlywayConfig.java" ]] \
    || find "$module_dir/src/main/java" -path '*/internal/config/FlywayConfig.java' | grep -q .; then
    custom_flyway="yes"
  else
    custom_flyway="no"
  fi

  db_name="$(detect_db_name "$module_name")"
  extensions="$(detect_extensions "$migration_dir")"
  parity_count="$(count_parity_migrations "$migration_dir")"

  print_row "$module_name" "$db_name" "$versioned_count" "$repeatable_count" \
    "${first_version:-none}" "${last_version:-none}" "$h2_layout" "$custom_flyway" "$extensions" "$parity_count"
done

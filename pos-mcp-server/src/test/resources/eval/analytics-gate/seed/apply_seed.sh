#!/usr/bin/env bash
# Apply the TRACKB analytics-gate seed to alpha's Postgres, one database at a time.
#
# Usage:  ./apply_seed.sh [db ...]        (default: all five, in dependency-free order)
#
# Reads credentials LITERALLY from /opt/durion/alpha/.env (grep/cut — the values
# contain '$', so the file must never be sourced). Applies each seed/sql/<db>.sql via
# `docker exec -i postgres-positivity psql -X` with ON_ERROR_STOP=1 (-X so a container
# `~/.psqlrc` cannot alter formatting or behaviour — the ground-truth output is the gate's
# reference sheet, so it must not depend on ambient state); each file is a single
# BEGIN/COMMIT transaction ending in a row-count summary SELECT, which is the per-file
# report this script prints.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL_DIR="$SCRIPT_DIR/sql"
ENV_FILE="${ALPHA_ENV_FILE:-/opt/durion/alpha/.env}"
CONTAINER="${POSTGRES_CONTAINER:-postgres-positivity}"

if [[ ! -f "$ENV_FILE" ]]; then
    echo "ERROR: env file not found: $ENV_FILE" >&2
    exit 1
fi

# Literal reads — never `source` this file: passwords contain '$'.
PGUSER="$(grep -m1 '^POSTGRES_USER=' "$ENV_FILE" | cut -d= -f2- || true)"
PGPASSWORD="$(grep -m1 '^POSTGRES_PASSWORD=' "$ENV_FILE" | cut -d= -f2- || true)"
PGUSER="${PGUSER:-postgres}"
if [[ -z "$PGPASSWORD" ]]; then
    echo "ERROR: POSTGRES_PASSWORD not found in $ENV_FILE" >&2
    exit 1
fi

DBS=("$@")
if [[ ${#DBS[@]} -eq 0 ]]; then
    DBS=(pos_customer_db pos_people_db pos_workorder_db pos_invoice_db pos_accounting_db)
fi

for db in "${DBS[@]}"; do
    file="$SQL_DIR/$db.sql"
    if [[ ! -f "$file" ]]; then
        echo "ERROR: no seed file for $db ($file)" >&2
        exit 1
    fi
    echo "==== $db  ($(grep -cE '^INSERT INTO' "$file") inserts) ===="
    docker exec -i -e PGPASSWORD="$PGPASSWORD" "$CONTAINER" \
        psql -X -U "$PGUSER" -d "$db" -v ON_ERROR_STOP=1 -q < "$file"
    echo
done
echo "All seed files applied."

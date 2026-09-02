#!/usr/bin/env bash
# Run the analytics-gate ground-truth SQL suite against the TRACKB-seeded Postgres.
#
# Usage:  ./run_ground_truth.sh [qNN-slug.sql ...]     (default: every qNN-*.sql, in order)
#
# Each script is split on its `-- DB: <database>` section markers (a single-database script
# carries exactly one marker; a multi-database question like Q5 carries one per section —
# there are no cross-database joins, per the repo's no-cross-service-FK rule). Every section
# is piped separately into `docker exec -i $CONTAINER psql -X -v ON_ERROR_STOP=1` against the
# database its marker names, and the output is labeled per question/section.
#
# Credentials are read LITERALLY from /opt/durion/alpha/.env with grep/cut — the values
# contain '$', so the file must never be sourced (same rule as ../seed/apply_seed.sh).
#
# Scripts default their own date parameters to the seed's EVAL_AS_OF (2026-09-01) via
# `\if :{?var}` guards; override by exporting PSQL_EXTRA_VARS, e.g.
#   PSQL_EXTRA_VARS='-v as_of_date='"'"'2026-06-30'"'"'' ./run_ground_truth.sh q13-ar-pareto.sql
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
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

SCRIPTS=("$@")
if [[ ${#SCRIPTS[@]} -eq 0 ]]; then
    mapfile -t SCRIPTS < <(cd "$SCRIPT_DIR" && ls q[0-9][0-9]-*.sql | sort)
fi

FAILURES=0
for script in "${SCRIPTS[@]}"; do
    file="$SCRIPT_DIR/${script##*/}"
    if [[ ! -f "$file" ]]; then
        echo "ERROR: no such ground-truth script: $file" >&2
        exit 1
    fi
    if ! grep -q '^-- DB: ' "$file"; then
        echo "ERROR: $script has no '-- DB: <database>' marker; refusing to guess" >&2
        exit 1
    fi
    # Section list: "<lineno>:<db>" per marker.
    mapfile -t markers < <(grep -n '^-- DB: ' "$file" | sed 's/^\([0-9]*\):-- DB: */\1:/')
    total_lines=$(wc -l < "$file")
    for idx in "${!markers[@]}"; do
        start_line="${markers[$idx]%%:*}"
        db="${markers[$idx]#*:}"
        db="${db//[$'\r ']/}"
        if (( idx + 1 < ${#markers[@]} )); then
            next="${markers[$((idx+1))]%%:*}"
            end_line=$((next - 1))
        else
            end_line=$total_lines
        fi
        echo "==== ${script##*/}  [section $((idx+1)) -> $db] ===="
        if ! sed -n "${start_line},${end_line}p" "$file" \
            | docker exec -i -e PGPASSWORD="$PGPASSWORD" "$CONTAINER" \
                psql -X -U "$PGUSER" -d "$db" -v ON_ERROR_STOP=1 ${PSQL_EXTRA_VARS:-}; then
            echo "FAILED: ${script##*/} section $((idx+1)) ($db)" >&2
            FAILURES=$((FAILURES + 1))
        fi
        echo
    done
done

if (( FAILURES > 0 )); then
    echo "$FAILURES section(s) failed." >&2
    exit 1
fi
echo "All ground-truth sections completed."

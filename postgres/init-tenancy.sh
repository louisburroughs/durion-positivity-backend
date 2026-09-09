#!/bin/bash
# Runs once on a fresh Postgres volume, after init-databases.sql (docker-entrypoint runs the
# init directory in name order). Two things, both from ADR-0062:
#
# 1. The single shared application role `pos_app`: LOGIN, not a superuser, NOBYPASSRLS, owns
#    nothing, DML on every service database. Services switch their datasource to it in plan WS1
#    (TenantAwareDataSource); the owner credential stays Flyway-only from then on.
# 2. TRANSITIONAL: until WS1 binds app.current_tenant per checkout, the owner role the services
#    still connect as carries the alpha default tenant as a role-level setting. Every scoped table
#    defaults tenant_id from that setting. Remove the ALTER ROLE line in WS1; with nothing bound,
#    scoped tables read as empty and refuse inserts (fail closed).
set -euo pipefail
DEFAULT_TENANT_ID=01900000-0000-7000-8000-000000000001
POS_APP_PASSWORD="${POS_APP_PASSWORD:-$POSTGRES_PASSWORD}"

if [ "$(psql -Atq -U "$POSTGRES_USER" -d postgres -c "SELECT 1 FROM pg_roles WHERE rolname = 'pos_app'")" != "1" ]; then
  psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -v pw="$POS_APP_PASSWORD" \
    -c "CREATE ROLE pos_app LOGIN PASSWORD :'pw' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOINHERIT"
fi

# TRANSITIONAL (plan WS1 removes this line)
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres \
  -c "ALTER ROLE \"$POSTGRES_USER\" SET app.current_tenant = '$DEFAULT_TENANT_ID'"

for db in $(psql -Atq -U "$POSTGRES_USER" -d postgres -c "SELECT datname FROM pg_database WHERE datname LIKE 'pos_%'"); do
  psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$db" <<SQL
GRANT CONNECT ON DATABASE "$db" TO pos_app;
GRANT USAGE ON SCHEMA public TO pos_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO pos_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO pos_app;
ALTER DEFAULT PRIVILEGES FOR ROLE "$POSTGRES_USER" IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO pos_app;
ALTER DEFAULT PRIVILEGES FOR ROLE "$POSTGRES_USER" IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO pos_app;
SQL
done

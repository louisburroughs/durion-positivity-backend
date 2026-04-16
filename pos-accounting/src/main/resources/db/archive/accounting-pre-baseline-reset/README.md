Archived `pos-accounting` versioned Flyway chain preserved during the collapsed
baseline reset.

These files are intentionally kept outside `db/migration` so Flyway does not
execute them after the dump-generated `V1__baseline_accounting_schema.sql`
became the active bootstrap path for disposable environments.

cd /opt/durion/alpha/backend

sudo docker compose -f docker-compose.yml -f /opt/durion/alpha/docker-compose.prod.yml \
  --env-file /opt/durion/alpha/.env exec postgres sh -lc \
  'psql -U "$POSTGRES_USER" -d postgres -c "DROP DATABASE IF EXISTS pos_accounting_db WITH (FORCE);"'

sudo docker compose -f docker-compose.yml -f /opt/durion/alpha/docker-compose.prod.yml \
  --env-file /opt/durion/alpha/.env exec postgres sh -lc \
  'psql -U "$POSTGRES_USER" -d postgres -c "CREATE DATABASE pos_accounting_db;"'

sudo docker compose -f docker-compose.yml -f /opt/durion/alpha/docker-compose.prod.yml \
  --env-file /opt/durion/alpha/.env up -d --force-recreate pos-accounting

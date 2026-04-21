#!/bin/bash
# Creates the application database user and grants privileges on all service databases.
# Runs once on fresh volume initialisation (docker-entrypoint-initdb.d).
# Requires SPRING_DATASOURCE_USERNAME and SPRING_DATASOURCE_PASSWORD env vars
# to be passed into the postgres container.
# NOTE: This script is a fallback. init-databases.sql already handles user creation.

APP_USER="${SPRING_DATASOURCE_USERNAME:-pos_user}"
APP_PASS="${SPRING_DATASOURCE_PASSWORD}"

psql --username "$POSTGRES_USER" <<-EOSQL
    DO \$\$ BEGIN
      IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '${APP_USER}') THEN
        CREATE USER ${APP_USER} WITH PASSWORD '${APP_PASS}';
      END IF;
    END \$\$;
    GRANT ALL PRIVILEGES ON DATABASE pos_accounting_db TO ${APP_USER};
    GRANT ALL PRIVILEGES ON DATABASE pos_catalog_db TO ${APP_USER};
    GRANT ALL PRIVILEGES ON DATABASE pos_customer_db TO ${APP_USER};
    GRANT ALL PRIVILEGES ON DATABASE pos_event_receiver_db TO ${APP_USER};
    GRANT ALL PRIVILEGES ON DATABASE pos_image_db TO ${APP_USER};
    GRANT ALL PRIVILEGES ON DATABASE pos_inquiry_db TO ${APP_USER};
    GRANT ALL PRIVILEGES ON DATABASE pos_inventory_db TO ${APP_USER};
    GRANT ALL PRIVILEGES ON DATABASE pos_invoice_db TO ${APP_USER};
    GRANT ALL PRIVILEGES ON DATABASE pos_location_db TO ${APP_USER};
    GRANT ALL PRIVILEGES ON DATABASE pos_mcp TO ${APP_USER};
    GRANT ALL PRIVILEGES ON DATABASE pos_order_db TO ${APP_USER};
    GRANT ALL PRIVILEGES ON DATABASE pos_people_db TO ${APP_USER};
    GRANT ALL PRIVILEGES ON DATABASE pos_price_db TO ${APP_USER};
    GRANT ALL PRIVILEGES ON DATABASE pos_security_db TO ${APP_USER};
    GRANT ALL PRIVILEGES ON DATABASE pos_shop_manager_db TO ${APP_USER};
    GRANT ALL PRIVILEGES ON DATABASE pos_vehicle_fitment_db TO ${APP_USER};
    GRANT ALL PRIVILEGES ON DATABASE pos_vehicle_inventory_db TO ${APP_USER};
    GRANT ALL PRIVILEGES ON DATABASE pos_vehicle_reference_carapi_db TO ${APP_USER};
    GRANT ALL PRIVILEGES ON DATABASE pos_vehicle_reference_nhtsa_db TO ${APP_USER};
    GRANT ALL PRIVILEGES ON DATABASE pos_workorder_db TO ${APP_USER};
EOSQL

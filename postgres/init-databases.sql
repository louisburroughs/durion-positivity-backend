-- PostgreSQL init script: create per-service databases
-- Mounted at /docker-entrypoint-initdb.d/ and runs once on fresh volume initialization.
-- All databases are owned by the POSTGRES_USER superuser.
CREATE DATABASE pos_accounting_db;
CREATE DATABASE pos_bulk_loader_db;
CREATE DATABASE pos_catalog_db;
CREATE DATABASE pos_customer_db;
CREATE DATABASE pos_event_receiver_db;
CREATE DATABASE pos_image_db;
CREATE DATABASE pos_inquiry_db;
CREATE DATABASE pos_inventory_db;
CREATE DATABASE pos_invoice_db;
CREATE DATABASE pos_location_db;
CREATE DATABASE pos_mcp;
CREATE DATABASE pos_order_db;
CREATE DATABASE pos_people_db;
CREATE DATABASE pos_people_contact_db;
CREATE DATABASE pos_price_db;
CREATE DATABASE pos_security_db;
CREATE DATABASE pos_shop_manager_db;
CREATE DATABASE pos_vehicle_fitment_db;
CREATE DATABASE pos_vehicle_inventory_db;
CREATE DATABASE pos_vehicle_reference_carapi_db;
CREATE DATABASE pos_vehicle_reference_nhtsa_db;
CREATE DATABASE pos_warranty_db;
CREATE DATABASE pos_workorder_db;

-- Create application user (matches SPRING_DATASOURCE_USERNAME / SPRING_DATASOURCE_PASSWORD)
CREATE USER pos_user WITH PASSWORD 's3cur1ty!';
GRANT ALL PRIVILEGES ON DATABASE pos_accounting_db TO pos_user;
GRANT ALL PRIVILEGES ON DATABASE pos_catalog_db TO pos_user;
GRANT ALL PRIVILEGES ON DATABASE pos_customer_db TO pos_user;
GRANT ALL PRIVILEGES ON DATABASE pos_event_receiver_db TO pos_user;
GRANT ALL PRIVILEGES ON DATABASE pos_image_db TO pos_user;
GRANT ALL PRIVILEGES ON DATABASE pos_inquiry_db TO pos_user;
GRANT ALL PRIVILEGES ON DATABASE pos_inventory_db TO pos_user;
GRANT ALL PRIVILEGES ON DATABASE pos_invoice_db TO pos_user;
GRANT ALL PRIVILEGES ON DATABASE pos_location_db TO pos_user;
GRANT ALL PRIVILEGES ON DATABASE pos_mcp TO pos_user;
GRANT ALL PRIVILEGES ON DATABASE pos_order_db TO pos_user;
GRANT ALL PRIVILEGES ON DATABASE pos_people_db TO pos_user;
GRANT ALL PRIVILEGES ON DATABASE pos_price_db TO pos_user;
GRANT ALL PRIVILEGES ON DATABASE pos_security_db TO pos_user;
GRANT ALL PRIVILEGES ON DATABASE pos_shop_manager_db TO pos_user;
GRANT ALL PRIVILEGES ON DATABASE pos_vehicle_fitment_db TO pos_user;
GRANT ALL PRIVILEGES ON DATABASE pos_vehicle_inventory_db TO pos_user;
GRANT ALL PRIVILEGES ON DATABASE pos_vehicle_reference_carapi_db TO pos_user;
GRANT ALL PRIVILEGES ON DATABASE pos_vehicle_reference_nhtsa_db TO pos_user;
GRANT ALL PRIVILEGES ON DATABASE pos_workorder_db TO pos_user;

-- PostgreSQL 15+: grant public schema CREATE to app user in each database
-- (connect to each db and grant schema privileges)
\c pos_accounting_db
GRANT ALL ON SCHEMA public TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO pos_user;
\c pos_catalog_db
GRANT ALL ON SCHEMA public TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO pos_user;
\c pos_customer_db
GRANT ALL ON SCHEMA public TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO pos_user;
\c pos_event_receiver_db
GRANT ALL ON SCHEMA public TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO pos_user;
\c pos_image_db
GRANT ALL ON SCHEMA public TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO pos_user;
\c pos_inquiry_db
GRANT ALL ON SCHEMA public TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO pos_user;
\c pos_inventory_db
GRANT ALL ON SCHEMA public TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO pos_user;
\c pos_invoice_db
GRANT ALL ON SCHEMA public TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO pos_user;
\c pos_location_db
GRANT ALL ON SCHEMA public TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO pos_user;
\c pos_mcp
GRANT ALL ON SCHEMA public TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO pos_user;
\c pos_order_db
GRANT ALL ON SCHEMA public TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO pos_user;
\c pos_people_db
GRANT ALL ON SCHEMA public TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO pos_user;
\c pos_price_db
GRANT ALL ON SCHEMA public TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO pos_user;
\c pos_security_db
GRANT ALL ON SCHEMA public TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO pos_user;
\c pos_shop_manager_db
GRANT ALL ON SCHEMA public TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO pos_user;
\c pos_vehicle_fitment_db
GRANT ALL ON SCHEMA public TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO pos_user;
\c pos_vehicle_inventory_db
GRANT ALL ON SCHEMA public TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO pos_user;
\c pos_vehicle_reference_carapi_db
GRANT ALL ON SCHEMA public TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO pos_user;
\c pos_vehicle_reference_nhtsa_db
GRANT ALL ON SCHEMA public TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO pos_user;
\c pos_workorder_db
GRANT ALL ON SCHEMA public TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO pos_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO pos_user;

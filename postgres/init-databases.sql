-- PostgreSQL init script: create per-service databases
-- Mounted at /docker-entrypoint-initdb.d/ and runs once on fresh volume initialization.
-- All databases are owned by the POSTGRES_USER superuser.
CREATE DATABASE pos_accounting_db;
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
CREATE DATABASE pos_price_db;
CREATE DATABASE pos_security_db;
CREATE DATABASE pos_shop_manager_db;
CREATE DATABASE pos_vehicle_fitment_db;
CREATE DATABASE pos_vehicle_inventory_db;
CREATE DATABASE pos_vehicle_reference_carapi_db;
CREATE DATABASE pos_vehicle_reference_nhtsa_db;
CREATE DATABASE pos_workorder_db;

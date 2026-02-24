-- CAP-215 Story #37: Add stock movement tracking fields to inventory_ledger_entry
ALTER TABLE IF EXISTS inventory_ledger_entry ADD COLUMN IF NOT EXISTS from_location_id VARCHAR(255);
ALTER TABLE IF EXISTS inventory_ledger_entry ADD COLUMN IF NOT EXISTS to_location_id VARCHAR(255);
ALTER TABLE IF EXISTS inventory_ledger_entry ADD COLUMN IF NOT EXISTS reason_code VARCHAR(100);
ALTER TABLE IF EXISTS inventory_ledger_entry ADD COLUMN IF NOT EXISTS source_transaction_id VARCHAR(255);
ALTER TABLE IF EXISTS inventory_ledger_entry ADD COLUMN IF NOT EXISTS unit_of_measure VARCHAR(50);

-- CAP-215 Story #37: Create inventory_adjustment_request table
CREATE TABLE IF NOT EXISTS inventory_adjustment_request (
    adjustment_request_id UUID PRIMARY KEY,
    product_sku VARCHAR(255) NOT NULL,
    location_id VARCHAR(255) NOT NULL,
    quantity INTEGER NOT NULL,
    reason_code VARCHAR(100) NOT NULL,
    unit_of_measure VARCHAR(50),
    status VARCHAR(50) NOT NULL,
    requested_by_user_id VARCHAR(255) NOT NULL,
    approved_by_user_id VARCHAR(255),
    requested_at TIMESTAMP NOT NULL,
    approved_at TIMESTAMP
);

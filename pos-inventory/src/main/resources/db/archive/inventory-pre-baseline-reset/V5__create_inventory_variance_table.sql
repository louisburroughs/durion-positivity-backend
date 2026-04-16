-- CAP-216 Story #34: Create inventory_variance table

CREATE TABLE IF NOT EXISTS inventory_variance (
    variance_id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES receiving_session(session_id),
    line_id UUID NOT NULL REFERENCES receiving_line(line_id),
    product_id VARCHAR(255) NOT NULL,
    variance_type VARCHAR(50) NOT NULL,
    variance_quantity NUMERIC(10,2) NOT NULL,
    expected_quantity NUMERIC(10,2) NOT NULL,
    received_quantity NUMERIC(10,2) NOT NULL,
    recorded_by_user_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_inventory_variance_session ON inventory_variance(session_id);
CREATE INDEX IF NOT EXISTS idx_inventory_variance_line ON inventory_variance(line_id);
-- CAP-216: Create receiving session and receiving line tables

CREATE TABLE IF NOT EXISTS receiving_session (
    session_id UUID PRIMARY KEY,
    source_document_id VARCHAR(255) NOT NULL,
    source_document_type VARCHAR(50) NOT NULL,
    supplier_id VARCHAR(255),
    shipment_reference VARCHAR(255),
    status VARCHAR(50) NOT NULL,
    entry_method VARCHAR(50) NOT NULL,
    created_by_user_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS receiving_line (
    line_id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES receiving_session(session_id),
    product_id VARCHAR(255) NOT NULL,
    expected_quantity NUMERIC(10,2) NOT NULL,
    received_quantity NUMERIC(10,2) NOT NULL DEFAULT 0,
    status VARCHAR(50) NOT NULL DEFAULT 'EXPECTED',
    workorder_id VARCHAR(255),
    workorder_line_id VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_receiving_session_source_doc ON receiving_session(source_document_id, source_document_type);
CREATE INDEX IF NOT EXISTS idx_receiving_line_session ON receiving_line(session_id);
CREATE INDEX IF NOT EXISTS idx_receiving_line_product ON receiving_line(product_id);

CREATE TABLE IF NOT EXISTS putaway_task (
    task_id UUID PRIMARY KEY,
    source_receipt_id UUID NOT NULL,
    product_id UUID NOT NULL,
    quantity INTEGER NOT NULL DEFAULT 1,
    source_location_id UUID NOT NULL,
    suggested_destination_location_id UUID,
    original_suggested_location_id UUID,
    final_suggested_location_id UUID,
    actual_destination_location_id UUID,
    fallback_reason VARCHAR(100),
    status VARCHAR(50) NOT NULL,
    assignee_id VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS putaway_rule (
    rule_id UUID PRIMARY KEY,
    priority INTEGER NOT NULL,
    criteria TEXT,
    destination_location_id UUID NOT NULL,
    is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Indexes for common query patterns
CREATE INDEX IF NOT EXISTS idx_putaway_task_source_receipt ON putaway_task(source_receipt_id);
CREATE INDEX IF NOT EXISTS idx_putaway_task_status ON putaway_task(status);
CREATE INDEX IF NOT EXISTS idx_putaway_task_product_dest_status ON putaway_task(product_id, suggested_destination_location_id, status);

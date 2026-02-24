CREATE TABLE IF NOT EXISTS putaway_task (
    task_id UUID PRIMARY KEY,
    source_receipt_id UUID NOT NULL,
    product_id UUID NOT NULL,
    quantity INTEGER NOT NULL DEFAULT 1,
    source_location_id VARCHAR(255) NOT NULL,
    suggested_destination_location_id VARCHAR(255),
    original_suggested_location_id VARCHAR(255),
    final_suggested_location_id VARCHAR(255),
    actual_destination_location_id VARCHAR(255),
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
    destination_location_id VARCHAR(255) NOT NULL,
    is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
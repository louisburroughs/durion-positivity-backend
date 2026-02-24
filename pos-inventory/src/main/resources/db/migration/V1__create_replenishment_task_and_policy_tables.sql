CREATE TABLE IF NOT EXISTS replenishment_task (
    task_id UUID PRIMARY KEY,
    item_sku VARCHAR(255) NOT NULL,
    quantity INTEGER NOT NULL,
    source_location_id VARCHAR(255) NOT NULL,
    destination_location_id VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    trigger_type VARCHAR(50) NOT NULL,
    decision_reason VARCHAR(100),
    sourcing_reason VARCHAR(100),
    assigned_to VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS replenishment_policy (
    policy_id UUID PRIMARY KEY,
    location_id VARCHAR(255) NOT NULL,
    item_sku VARCHAR(255) NOT NULL,
    minimum_quantity INTEGER NOT NULL,
    maximum_quantity INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (location_id, item_sku)
);

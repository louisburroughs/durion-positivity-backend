CREATE TABLE timekeeping_entry (
    timekeeping_entry_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    source_system VARCHAR(50) NOT NULL DEFAULT 'shopmgr',
    source_session_id UUID NOT NULL,
    employee_id UUID NOT NULL,
    session_start_time TIMESTAMP WITH TIME ZONE NOT NULL,
    session_end_time TIMESTAMP WITH TIME ZONE NOT NULL,
    approval_status VARCHAR(50) NOT NULL DEFAULT 'PENDING_APPROVAL',
    associated_work_order_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_timekeeping_entry_idempotency UNIQUE (tenant_id, source_system, source_session_id)
);
CREATE INDEX idx_timekeeping_entry_tenant_session ON timekeeping_entry(tenant_id, source_session_id);
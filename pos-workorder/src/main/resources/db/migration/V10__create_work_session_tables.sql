-- CAP-139 Story #68 - WorkSession Start/Stop
-- Creates tables for mechanic work sessions and break segments.

CREATE TABLE work_session (
    work_session_id UUID NOT NULL,
    mechanic_id UUID NOT NULL,
    work_order_id UUID NOT NULL,
    work_order_task_id UUID NOT NULL,
    location_id UUID NOT NULL,
    resource_id UUID,
    start_at TIMESTAMP WITH TIME ZONE NOT NULL,
    end_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(50) NOT NULL,
    locked BOOLEAN NOT NULL DEFAULT FALSE,
    total_duration_seconds INT NOT NULL DEFAULT 0,
    approved_at TIMESTAMP WITH TIME ZONE,
    approved_by_user_id VARCHAR(255),
    approval_notes TEXT,
    locked_at TIMESTAMP WITH TIME ZONE,
    overlap_override_used BOOLEAN NOT NULL DEFAULT FALSE,
    override_reason TEXT,
    overridden_by_user_id VARCHAR(255),
    override_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_work_session PRIMARY KEY (work_session_id)
);

CREATE TABLE break_segment (
    break_segment_id UUID NOT NULL,
    work_session_id UUID NOT NULL,
    break_start_at TIMESTAMP WITH TIME ZONE NOT NULL,
    break_end_at TIMESTAMP WITH TIME ZONE,
    break_type VARCHAR(50),
    notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_break_segment PRIMARY KEY (break_segment_id),
    CONSTRAINT fk_break_segment_work_session
        FOREIGN KEY (work_session_id) REFERENCES work_session(work_session_id) ON DELETE CASCADE
);

CREATE INDEX idx_work_session_mechanic_id ON work_session(mechanic_id);
CREATE INDEX idx_work_session_work_order_id ON work_session(work_order_id);
CREATE INDEX idx_work_session_status ON work_session(status);
CREATE INDEX idx_break_segment_work_session_id ON break_segment(work_session_id);

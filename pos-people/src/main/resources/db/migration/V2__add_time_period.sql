SET TIME ZONE 'UTC';

CREATE TABLE time_period (
    time_period_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    start_date date NOT NULL,
    end_date date NOT NULL,
    status character varying(50) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT time_period_pkey PRIMARY KEY (time_period_id),
    CONSTRAINT time_period_status_check CHECK (status IN ('OPEN', 'SUBMISSION_CLOSED', 'PAYROLL_CLOSED'))
);

CREATE INDEX idx_time_period_tenant_status ON time_period USING btree (tenant_id, status);
CREATE INDEX idx_time_period_dates ON time_period USING btree (start_date, end_date);

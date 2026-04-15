-- Align placeholder parity tables from V10 with the current JPA entities.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'audit_log_events'
          AND column_name = 'id'
    ) AND NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'audit_log_events'
          AND column_name = 'event_id'
    ) THEN
        ALTER TABLE audit_log_events RENAME COLUMN id TO event_id;
    END IF;
END $$;

ALTER TABLE audit_log_events
    ADD COLUMN IF NOT EXISTS timestamp TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS event_type VARCHAR(100),
    ADD COLUMN IF NOT EXISTS actor_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS entity_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS entity_type VARCHAR(255),
    ADD COLUMN IF NOT EXISTS old_value TEXT,
    ADD COLUMN IF NOT EXISTS new_value TEXT,
    ADD COLUMN IF NOT EXISTS context TEXT,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX IF NOT EXISTS idx_audit_log_events_timestamp ON audit_log_events (timestamp);
CREATE INDEX IF NOT EXISTS idx_audit_log_events_actor_id ON audit_log_events (actor_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_events_entity_id ON audit_log_events (entity_id);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'pricing_snapshots'
          AND column_name = 'id'
    ) AND NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'pricing_snapshots'
          AND column_name = 'snapshot_id'
    ) THEN
        ALTER TABLE pricing_snapshots RENAME COLUMN id TO snapshot_id;
    END IF;
END $$;

ALTER TABLE pricing_snapshots
    ADD COLUMN IF NOT EXISTS timestamp TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS quote_context TEXT,
    ADD COLUMN IF NOT EXISTS final_price NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE pricing_rule_trace_entries
    ADD COLUMN IF NOT EXISTS snapshot_id UUID,
    ADD COLUMN IF NOT EXISTS rule_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS status VARCHAR(32),
    ADD COLUMN IF NOT EXISTS inputs TEXT,
    ADD COLUMN IF NOT EXISTS outputs TEXT,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_pricing_rule_trace_entries_snapshot'
    ) THEN
        ALTER TABLE pricing_rule_trace_entries
            ADD CONSTRAINT fk_pricing_rule_trace_entries_snapshot
                FOREIGN KEY (snapshot_id) REFERENCES pricing_snapshots(snapshot_id);
    END IF;
END $$;

ALTER TABLE principal_roles
    ADD COLUMN IF NOT EXISTS principal_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS role_id UUID,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_principal_roles_role'
    ) THEN
        ALTER TABLE principal_roles
            ADD CONSTRAINT fk_principal_roles_role
                FOREIGN KEY (role_id) REFERENCES roles(id);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uk_principal_roles_principal_id_role_id'
    ) THEN
        ALTER TABLE principal_roles
            ADD CONSTRAINT uk_principal_roles_principal_id_role_id
                UNIQUE (principal_id, role_id);
    END IF;
END $$;

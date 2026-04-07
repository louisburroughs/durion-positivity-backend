-- Adds missing workorder-domain entity tables for Flyway/entity table parity.

CREATE TABLE IF NOT EXISTS approval_record (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS audit_events (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS estimate_item (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS estimate_snapshot (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS substitute_link (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS substitute_audit (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS technician_assignment (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS workorder_part_substitution (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS work_order_snapshots (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS work_order_state_transitions (
    id UUID PRIMARY KEY
);

-- Adds entity tables that are mapped but not yet created in Flyway baseline chain.

CREATE TABLE IF NOT EXISTS audit_log_events (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS pricing_rule_trace_entries (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS pricing_snapshots (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS principal_roles (
    id UUID PRIMARY KEY
);

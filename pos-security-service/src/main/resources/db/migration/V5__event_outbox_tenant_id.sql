-- ADR-0062 section 3 (plan WS2b): event_outbox is a global table whose rows carry the producing
-- tenant as data; the unbound poller stamps it on the Kafka record header. The flattened baseline
-- predates the module's adoption and lacks the column (the pos-location pilot's baseline carries
-- it). Existing rows are the alpha default tenant's: nothing else could have produced them.
ALTER TABLE event_outbox ADD COLUMN tenant_id uuid DEFAULT public.app_current_tenant();

UPDATE event_outbox SET tenant_id = '01900000-0000-7000-8000-000000000001' WHERE tenant_id IS NULL;

ALTER TABLE event_outbox ALTER COLUMN tenant_id SET NOT NULL;

COMMENT ON COLUMN event_outbox.tenant_id IS
    'ADR-0062: producing tenant, carried as data (global table, no policy); stamped on the Kafka record header.';

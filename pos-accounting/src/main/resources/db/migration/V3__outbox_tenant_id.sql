-- ADR-0062 section 3 (plan WS3): both outboxes are global tables whose rows carry the producing
-- tenant as data. kafka_event_outbox is drained unbound by OutboxPublisher, which stamps tenant_id on
-- the Kafka record header; event_outbox is drained unbound by OutboxProcessor, which binds tenant_id
-- before dispatching each event to its GL-posting handler. The flattened baseline predates the
-- module's adoption and lacks the column. Existing rows are the alpha default tenant's: nothing else
-- could have produced them.
ALTER TABLE event_outbox ADD COLUMN tenant_id uuid DEFAULT public.app_current_tenant();
ALTER TABLE kafka_event_outbox ADD COLUMN tenant_id uuid DEFAULT public.app_current_tenant();

UPDATE event_outbox SET tenant_id = '01900000-0000-7000-8000-000000000001' WHERE tenant_id IS NULL;
UPDATE kafka_event_outbox SET tenant_id = '01900000-0000-7000-8000-000000000001' WHERE tenant_id IS NULL;

ALTER TABLE event_outbox ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE kafka_event_outbox ALTER COLUMN tenant_id SET NOT NULL;

COMMENT ON COLUMN event_outbox.tenant_id IS
    'ADR-0062: producing tenant, carried as data (global table, no policy); bound before the event is dispatched.';
COMMENT ON COLUMN kafka_event_outbox.tenant_id IS
    'ADR-0062: producing tenant, carried as data (global table, no policy); stamped on the Kafka record header.';

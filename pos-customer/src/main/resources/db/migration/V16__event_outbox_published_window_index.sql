-- ADR-0044 §4 (#889/#894 review): the reconciliation ManifestPublisher scans published
-- rows of a topic by created_at window; without this index the scan degrades to a full
-- table walk as outbox history grows. Matches the pos-people-contact/pos-people outbox DDL.
CREATE INDEX idx_event_outbox_published_window ON event_outbox (topic, created_at)
    WHERE published_at IS NOT NULL;

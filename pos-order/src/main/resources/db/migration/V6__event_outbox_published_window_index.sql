-- #2579 (ADR-0044 section 4): the reconciliation manifest and its replay read event_outbox by topic and creation
-- window over published rows only (ManifestPublisher; OutboxEventRepository.findByTopicAndPublishedAtIsNotNull-
-- AndCreatedAtBetween, markForReplaySince, markForReplayBetween). idx_event_outbox_unpublished (published_at, id)
-- serves the drain, not these. The same partial index the other fact owners carry (pos-location, pos-inventory,
-- pos-customer, pos-people).

CREATE INDEX idx_event_outbox_published_window ON public.event_outbox USING btree (topic, created_at)
    WHERE (published_at IS NOT NULL);

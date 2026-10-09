-- CAP:550 S24 (#2517; ADR-0044 section 4): the supplier.manifest.v1 ManifestPublisher reads supplier_event_outbox by
-- topic and creation window over published rows only
-- (SupplierOutboxEventRepository.findByTopicAndPublishedAtIsNotNullAndCreatedAtBetween), and the
-- supplier.outbox.replay-requested repair re-queues the same window (markForReplayBetween). idx_soutbox_unpublished
-- (published_at, id) serves the drain, not these. The same partial index pos-order carries (its V6) and the other fact
-- owners. supplier_event_outbox is a global table (tenant_id is data, db/tenancy-global-tables.txt); no policy changes.

CREATE INDEX idx_supplier_event_outbox_published_window ON public.supplier_event_outbox USING btree (topic, created_at)
    WHERE (published_at IS NOT NULL);

-- Issue #1537 (D2): a nullable owner tag on processed_events so a reconciliation
-- listener can scope its window scan to the events one specific upstream listener
-- recorded.
--
-- Unlike other modules' processed_events (e.g. pos-customer), accounting's table is
-- shared by every one of this module's Kafka listeners (invoice/warranty/order/
-- inventory/customer/settlement/supplier-invoice events all write rows here) and
-- previously carried no owner column at all. InvoiceManifestListener needs to compare
-- against invoice.manifest.v1, whose owner-side checksum (pos-invoice's ManifestPublisher)
-- covers only invoice.events.v1 facts — so an unscoped comparison would be polluted by
-- every other listener's eventIds and drift permanently even on an intact replica.
--
-- Only InvoiceEventsListener stamps owner = 'invoice' going forward; every other
-- existing listener is left untouched and keeps writing rows with owner NULL, which the
-- invoice-scoped window query below excludes.
ALTER TABLE processed_events
    ADD COLUMN owner character varying(64);

CREATE INDEX idx_processed_events_owner_event ON processed_events (owner, event_id);

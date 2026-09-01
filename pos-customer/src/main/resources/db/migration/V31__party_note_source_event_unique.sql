-- Issue #1584: party_note gets a live writer again, so its redelivery guard has to be real.
--
-- V1 created idx_party_note_source_event as a plain btree index, while every sibling projection
-- (service_history V26, follow_up_task V24, customer_interaction V22) has a partial UNIQUE index on
-- source_event_id. WorkorderEventsListener's pre-check covers the ordinary redelivery; it does not
-- cover two consumer instances handling the same event concurrently during a rebalance, where both
-- see exists == false and insert. customer_interaction would dedupe on its unique index and
-- party_note would not, leaving the two projections of one fact disagreeing.
--
-- Safe to add without a backfill: the only writer this table has ever had was the handler for an
-- event no module published, so no duplicate source_event_id can exist.
DROP INDEX IF EXISTS idx_party_note_source_event;

CREATE UNIQUE INDEX uq_party_note_source_event
    ON party_note (source_event_id)
    WHERE source_event_id IS NOT NULL;

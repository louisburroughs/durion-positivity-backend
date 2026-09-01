-- Issue #1592 (E4): workorder-creation-to-invoice lag analytics needs the workorder's own
-- creation timestamp alongside the replica's updated_at. WorkorderUpdatedV1.createdAt has
-- always been on the wire (WorkorderFactPublisher.publishPending passes
-- workorder.getCreatedAt()) but WorkorderEventsListener dropped it when building the replica
-- row; this column gives it somewhere to land.
--
-- Nullable and never backfilled: rows replicated before this migration, or from an older event
-- that never carried createdAt, must read as "unknown lag anchor", not as a false zero. The
-- invoicing-lag query excludes NULL here from both the average and the count rather than
-- treating it as same-day.
-- H2-compatible: plain ADD COLUMN only.

ALTER TABLE ext_workorder ADD COLUMN workorder_created_at timestamp(6) with time zone;

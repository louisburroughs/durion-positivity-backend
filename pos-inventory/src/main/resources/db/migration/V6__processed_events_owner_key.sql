-- #2176: processed_events was keyed by event_id alone. OrderEventsListener and
-- PurchaseOrderProjectionListener both consume order.events.v1, so whichever recorded an event first
-- made the other skip it, and one of the two responsibilities (counter-sale stock postings, the
-- purchase-order projection) silently lost that event. The mark is per consumer now: the owner is
-- part of the key, and the projection records under an owner of its own.
--
-- Existing rows keep their owner; every row already has one (owner is NOT NULL), so the new key is
-- satisfied by the data as it stands.
ALTER TABLE processed_events DROP CONSTRAINT processed_events_pkey;
ALTER TABLE processed_events ADD CONSTRAINT processed_events_pkey PRIMARY KEY (event_id, owner);

-- #2009 (PR review): a receiving session's stock lands at one site, and that site decides which
-- staging location the session's ledger entries are stamped with. Deriving it from the source
-- order's current ship-to on every receive call would let a mid-session revision move it:
-- pos-order's revisePurchaseOrder overwrites shipToLocationId in any lifecycle state, PARTIALLY_
-- RECEIVED included, so lines received before and after such a revision would stage at different
-- locations under one session.
--
-- Nullable rather than backfilled: only sessions opened from here on can record the ship-to that
-- was true when they opened, and a null reads as "unknown", which falls back to the projection
-- lookup exactly as before this column existed.
ALTER TABLE receiving_session ADD COLUMN site_id uuid;

COMMENT ON COLUMN receiving_session.site_id IS
    'Site this session receives at, captured from the source order ship-to when the session opened (#2009). Null for sessions opened before the column existed.';

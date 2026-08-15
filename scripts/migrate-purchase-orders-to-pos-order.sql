-- CAP-320 #1334: move existing purchase orders from pos_inventory_db to pos_order_db.
--
-- This is a script rather than a Flyway migration because the two services own separate
-- databases. Nothing in pos-order's migration path can read pos-inventory's tables, and giving it
-- that reach would undo the separation this story exists to create.
--
-- Run it once, between deploying the pos-order half and deploying the pos-inventory half, with
-- both services stopped. Identity is preserved throughout: purchase_order_id and line_id keep
-- their values, so every advance shipping notice, goods receipt and suggestion that references an
-- order still resolves afterwards.
--
--   pg_dump --data-only --table=purchase_order --table=purchase_order_line pos_inventory_db \
--     | psql pos_order_db -f -
--   psql pos_order_db -f scripts/migrate-purchase-orders-to-pos-order.sql
--
-- The dump moves the rows; this script settles what the dump cannot: the number sequence, the
-- column pos-inventory carried that pos-order does not, and the facts that rebuild every
-- consumer's replica.

BEGIN;

-- 1. The number sequence must resume above every number already printed on a purchase order.
--    Restarting at 1 would mint a second PO-0000001A, and the two would be indistinguishable on
--    paper. Base-36 is decoded back to the sequence value it was rendered from.
SELECT setval(
    'purchase_order_number_seq',
    GREATEST(
        (SELECT COALESCE(MAX(
            -- 8-character base-36 → bigint
            (SELECT SUM(
                (POSITION(SUBSTRING(UPPER(po_number) FROM i FOR 1) IN '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ') - 1)
                * POWER(36, LENGTH(po_number) - i))
             FROM generate_series(1, LENGTH(po_number)) AS i)::bigint), 0)
         FROM purchase_order
         WHERE po_number ~ '^[0-9A-Z]+$'),
        1),
    true);

-- 2. encumbrance_ref was removed by #1332 as an unimplemented stub; a dump taken from a database
--    that predates it carries the column, and pos-order has no home for it.
ALTER TABLE purchase_order DROP COLUMN IF EXISTS encumbrance_ref;

-- 3. Every migrated order must be republished, or consumers keep the replicas they built from
--    pos-inventory's facts and never learn the owner changed. Bumping version_number makes each
--    order's next fact newer than anything already applied, so no consumer's stale guard rejects
--    it — without this the republish would be silently ignored by exactly the replicas that need
--    it most.
UPDATE purchase_order SET version_number = version_number + 1;

COMMIT;

-- 4. With this committed, run pos-order's republish task so one purchaseorder.updated fact goes
--    out per order. Verify before deleting anything on the pos-inventory side:
--
--    SELECT count(*) FROM purchase_order;                     -- in pos_order_db
--    SELECT count(*) FROM ext_purchase_order;                 -- in pos_inventory_db, must match
--    SELECT count(*) FROM purchase_order_line;                -- in pos_order_db
--    SELECT count(*) FROM ext_purchase_order_line;            -- in pos_inventory_db, must match
--
-- Only once those agree is pos-inventory's V37 safe to apply: it drops the source tables, and a
-- projection that has not caught up would leave availability-to-promise short by every order it
-- has not yet seen, with no way left to rebuild them.

-- #1461: product.attributes and product.specifications were mapped with @Lob, which on
-- Postgres stores a large-object OID rather than the text itself. Large objects cannot be
-- read in auto-commit mode (so any ProductEntity load outside a transaction fails) and are
-- not deleted with the referencing row (so updates and deletes leak pg_largeobject
-- storage). Both fields hold small JSON strings; convert the columns to plain text.

ALTER TABLE product ADD COLUMN attributes_text text;
ALTER TABLE product ADD COLUMN specifications_text text;

-- Copy the large-object contents into the text columns. The pg_largeobject_metadata guard
-- turns a dangling OID (object already unlinked) into NULL instead of failing the
-- migration.
UPDATE product p
SET attributes_text = convert_from(lo_get(p.attributes), 'UTF8')
WHERE p.attributes IS NOT NULL
  AND EXISTS (SELECT 1 FROM pg_largeobject_metadata m WHERE m.oid = p.attributes);

UPDATE product p
SET specifications_text = convert_from(lo_get(p.specifications), 'UTF8')
WHERE p.specifications IS NOT NULL
  AND EXISTS (SELECT 1 FROM pg_largeobject_metadata m WHERE m.oid = p.specifications);

-- Unlink every large object the old columns still reference, so this conversion leaves no
-- orphans of its own. UNION deduplicates OIDs shared across rows/columns so no OID is
-- unlinked twice.
SELECT lo_unlink(o.lo_oid)
FROM (
    SELECT attributes AS lo_oid FROM product WHERE attributes IS NOT NULL
    UNION
    SELECT specifications FROM product WHERE specifications IS NOT NULL
) o
WHERE EXISTS (SELECT 1 FROM pg_largeobject_metadata m WHERE m.oid = o.lo_oid);

ALTER TABLE product DROP COLUMN attributes;
ALTER TABLE product DROP COLUMN specifications;
ALTER TABLE product RENAME COLUMN attributes_text TO attributes;
ALTER TABLE product RENAME COLUMN specifications_text TO specifications;

-- Orphans leaked before this migration (rewrites and row deletes while the columns were
-- OIDs) are not reachable from any table and are swept separately with vacuumlo during the
-- maintenance window; see #1461.

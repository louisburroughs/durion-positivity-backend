-- #1637 decision 1: the product-keyed availability read accepts a catalog SKU as well as a
-- productId, and ADR-0044 R1 forbids resolving one with a synchronous call to pos-catalog. The
-- catalog.product.updated fact already carries the product's SKU; this module simply starts
-- keeping it, so a SKU resolves to a product entirely from the local replica (ADR-0044 R3),
-- exactly as the EAN/UPC codes already do.
--
-- Nullable forward-only: rows written before this migration hold NULL until the product's next
-- catalog.product.updated fact (or a replay) arrives. A NULL sku therefore means "not replicated
-- yet or the product has none", and the availability read reports it as an unresolvable identity
-- rather than guessing.
--
-- Stored uppercased by the consumer: pos-catalog keeps the SKU mixed-case but unique
-- case-insensitively, so the replica canonicalises on write and the lookup uppercases its input —
-- case-insensitive matching over the plain index below, on H2 and PostgreSQL alike.
--
-- H2(MODE=PostgreSQL)-compatible, matching V9.
ALTER TABLE ext_product_code ADD COLUMN sku character varying(64);

-- The sku -> product lookup. Deliberately NOT unique, for the same reason as
-- idx_ext_product_code_lookup: uniqueness is pos-catalog's invariant, and a duplicate arriving
-- here is a replication defect the resolver must see and refuse rather than have the database
-- reject mid-consumption.
CREATE INDEX idx_ext_product_code_sku ON ext_product_code (sku);

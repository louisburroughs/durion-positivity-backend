-- CAP-320 #1334: the purchase-order aggregate now lives in pos-order, so it is removed here.
--
-- Removed rather than deprecated. Two writable purchase orders is the failure this split exists to
-- prevent, and a table left in place is a table something eventually writes to. What the order says
-- is read from ext_purchase_order, which #1333 built and proved against these very tables.
--
-- The foreign keys go first. Receiving keeps its own documents — advance shipping notices, goods
-- receipts and their lines — and those now reference the order by plain id, because there is
-- nothing local left to join to. Losing referential integrity to the order is the price of the
-- split; it is paid deliberately, and the projection is what makes the reference resolvable.

ALTER TABLE advance_shipping_notice DROP CONSTRAINT IF EXISTS fk6nx0dqi0h7ggdxrs6y4pfjct7;
ALTER TABLE asn_line DROP CONSTRAINT IF EXISTS fkhvj0a61es04ch2n43p78gmwgy;
ALTER TABLE asn_line DROP CONSTRAINT IF EXISTS fkcqfd3khodb6cxkvifl9o6c26h;
ALTER TABLE goods_receipt DROP CONSTRAINT IF EXISTS fkhw1l11tclv7r2klx8gcxg8gu9;
ALTER TABLE goods_receipt_line DROP CONSTRAINT IF EXISTS fkrlwlvthauoc4gbscp505707d0;

DROP TABLE IF EXISTS purchase_order_line;
DROP TABLE IF EXISTS purchase_order;

-- The number sequence goes with the aggregate: pos-order draws from its own copy, started above
-- this one's high-water mark so no number can be issued twice.
DROP SEQUENCE IF EXISTS purchase_order_number_seq;

-- Receiving still needs to find its own documents by order, and the joins that used to serve that
-- are gone with the foreign keys.
CREATE INDEX IF NOT EXISTS idx_advance_shipping_notice_po ON advance_shipping_notice (po_id);
CREATE INDEX IF NOT EXISTS idx_asn_line_po ON asn_line (po_id);
CREATE INDEX IF NOT EXISTS idx_goods_receipt_po ON goods_receipt (po_id);

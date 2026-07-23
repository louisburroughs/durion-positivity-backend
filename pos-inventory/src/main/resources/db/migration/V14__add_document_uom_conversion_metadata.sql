-- odoo-parity B2 (#1034, spec §3 B2–B4): document-boundary UoM conversion metadata.
--
-- Document lines (PO, ASN, goods receipt, receiving, return) may be keyed in a
-- document UoM (e.g. 1 CASE); the service converts to the product's base UoM at
-- posting/derivation time using the catalog-fed ext_product_uom replica factors.
-- These nullable columns preserve the audit trail of what was keyed vs what was
-- posted: document_uom/document_quantity are the values as keyed on the document,
-- conversion_factor is the effective base-per-document-unit factor actually
-- applied (including rounding at the base UoM's precision scale). All three stay
-- NULL for lines keyed directly in base UoM — the pre-B2 behavior.

ALTER TABLE purchase_order_line ADD COLUMN document_uom character varying(32);
ALTER TABLE purchase_order_line ADD COLUMN document_quantity numeric(18,6);
ALTER TABLE purchase_order_line ADD COLUMN conversion_factor numeric(20,6);

ALTER TABLE asn_line ADD COLUMN document_uom character varying(32);
ALTER TABLE asn_line ADD COLUMN document_quantity numeric(18,6);
ALTER TABLE asn_line ADD COLUMN conversion_factor numeric(20,6);

ALTER TABLE goods_receipt_line ADD COLUMN document_uom character varying(32);
ALTER TABLE goods_receipt_line ADD COLUMN document_quantity numeric(18,6);
ALTER TABLE goods_receipt_line ADD COLUMN conversion_factor numeric(20,6);

ALTER TABLE receiving_line ADD COLUMN document_uom character varying(32);
ALTER TABLE receiving_line ADD COLUMN document_quantity numeric(18,6);
ALTER TABLE receiving_line ADD COLUMN conversion_factor numeric(20,6);

ALTER TABLE inventory_return_line ADD COLUMN document_uom character varying(32);
ALTER TABLE inventory_return_line ADD COLUMN document_quantity numeric(18,6);
ALTER TABLE inventory_return_line ADD COLUMN conversion_factor numeric(20,6);

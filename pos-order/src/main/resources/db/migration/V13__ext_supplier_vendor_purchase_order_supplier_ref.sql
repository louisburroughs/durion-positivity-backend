-- #2713 (CAP:550; Order ruling on #2682): the vendor's purchasing channel, pos-supplier's designated transmission
-- profile alias, copied from supplier.vendor.updated.purchaseOrderSupplierRef (#2712). pos-order stamps it on a
-- purchase order's supplier_ref at create, vendor-changing revise, approve and (while not yet transmitted) transmit,
-- so an order is sent through the channel its vendor has, without pos-order ever asking pos-supplier (ADR-0044 R1).
-- Null: the vendor has no channel (none, or several and none designated). INTERNAL under ADR-0072.
ALTER TABLE public.ext_supplier_vendor ADD COLUMN purchase_order_supplier_ref character varying(100);

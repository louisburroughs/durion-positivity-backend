-- #2515 (CAP:550 S19; SPEC-accounting-workspace aged payables, AW11 amendment of 2026-10-07): the replica of
-- pos-order's purchase orders that an estimated due date reads. A vendor bill with no due date ages from its
-- terms, and the purchase order's payment terms come first (PurchaseOrderUpdatedV1.paymentTermsId, schema 2).
--
-- One row per order, written only by the purchaseorder.updated facts on order.events.v1 and version-guarded on
-- the envelope's aggregate version (ReplicaVersionGuard: equal versions apply). payment_terms_id is nullable:
-- an order without terms, and every row written from a schema-1 fact, hold null. No backfill; rows fill as
-- pos-order publishes the order's next fact. It carries the full tenancy schema of TENANCY_SCHEMA.md
-- "Adding a table".
CREATE TABLE public.ext_order_purchase_order (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    purchase_order_id uuid NOT NULL,
    po_number character varying(255) NOT NULL,
    vendor_id uuid NOT NULL,
    payment_terms_id character varying(255),
    aggregate_version bigint DEFAULT 0 NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL
);

COMMENT ON TABLE public.ext_order_purchase_order IS
    'Read-only replica of pos-order purchase orders (#2515), written only by the purchaseorder.updated facts. '
    'Read for the payment terms an estimated vendor-bill due date takes first.';
COMMENT ON COLUMN public.ext_order_purchase_order.payment_terms_id IS
    'The owner''s payment terms text (NET30, DUE_ON_RECEIPT, ...); null when the order has none or the fact is '
    'schema 1. May not parse: the estimate then falls through to the vendor, then the tenant default.';

ALTER TABLE ONLY public.ext_order_purchase_order
    ADD CONSTRAINT ext_order_purchase_order_pkey PRIMARY KEY (purchase_order_id);
ALTER TABLE ONLY public.ext_order_purchase_order
    ADD CONSTRAINT ext_order_purchase_order_tenant_key UNIQUE (tenant_id, purchase_order_id);
CREATE INDEX ext_order_purchase_order_tenant_idx ON public.ext_order_purchase_order USING btree (tenant_id);

ALTER TABLE public.ext_order_purchase_order ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ext_order_purchase_order FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.ext_order_purchase_order
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

-- #2750 (CAP:550 S19 follow-up; ADR-0044 R3): the replica of pos-inventory's goods receipts that the
-- "deliveries not billed" work item reads.
--
-- One row per receipt, written only by the goodsreceipt.recorded facts on inventory.events.v1. The fact's
-- envelope aggregate version is always 0, so the row is not version-guarded: a corrected re-emit of the same
-- receipt updates the amount, currency and receipt number and keeps received_at, the first fact's occurredAt.
-- Every well-formed fact is recorded whatever its accrual outcome (held, skipped, nothing to post, posted); the
-- read filters. No backfill here (FORCE RLS): GoodsReceiptReplicaBackfill replays the stored facts at startup.
-- It carries the full tenancy schema of TENANCY_SCHEMA.md "Adding a table".
CREATE TABLE public.ext_inventory_goods_receipt (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    goods_receipt_id uuid NOT NULL,
    receipt_number character varying(255),
    purchase_order_id uuid NOT NULL,
    accrued_amount_minor bigint NOT NULL,
    currency_code character varying(3),
    received_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL
);

COMMENT ON TABLE public.ext_inventory_goods_receipt IS
    'Read-only replica of pos-inventory goods receipts (#2750), written only by the goodsreceipt.recorded facts. '
    'Read for deliveries that no approved bill covers.';
COMMENT ON COLUMN public.ext_inventory_goods_receipt.accrued_amount_minor IS
    'totalAccruedAmountMinor of the latest fact for the receipt, in minor units of currency_code.';
COMMENT ON COLUMN public.ext_inventory_goods_receipt.currency_code IS
    'The fact''s currency; null when it states none (ADR-0067 PC-9). Never defaulted.';
COMMENT ON COLUMN public.ext_inventory_goods_receipt.received_at IS
    'occurredAt of the FIRST fact seen for the receipt; a corrected re-emit does not move it.';

ALTER TABLE ONLY public.ext_inventory_goods_receipt
    ADD CONSTRAINT ext_inventory_goods_receipt_pkey PRIMARY KEY (goods_receipt_id);
ALTER TABLE ONLY public.ext_inventory_goods_receipt
    ADD CONSTRAINT ext_inventory_goods_receipt_tenant_key UNIQUE (tenant_id, goods_receipt_id);
CREATE INDEX ext_inventory_goods_receipt_tenant_idx ON public.ext_inventory_goods_receipt USING btree (tenant_id);
CREATE INDEX ext_inventory_goods_receipt_po_idx
    ON public.ext_inventory_goods_receipt USING btree (tenant_id, purchase_order_id);
CREATE INDEX ext_inventory_goods_receipt_received_idx
    ON public.ext_inventory_goods_receipt USING btree (tenant_id, received_at);

ALTER TABLE public.ext_inventory_goods_receipt ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ext_inventory_goods_receipt FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.ext_inventory_goods_receipt
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

-- The backfill's keyset read of stored goodsreceipt.recorded facts, oldest first (event_type, received_at, event_id).
CREATE INDEX idx_accounting_event_type_received_event
    ON public.accounting_event USING btree (event_type, received_at, event_id);

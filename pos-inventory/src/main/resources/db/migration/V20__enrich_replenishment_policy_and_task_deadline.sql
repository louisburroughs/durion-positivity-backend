-- odoo-parity F3 (#1041, spec F3/F6): replenishment policy enrichment + task deadline date.
--
-- Policy columns (all backward-safe — every pre-F3 row keeps its previous behaviour):
--   order_multiple          — round the computed replenishment quantity UP to the nearest
--                             multiple (Odoo qty_multiple); NULL means no rounding. Seeded
--                             from the vendor-feed pack size on policy creation when the
--                             caller supplies none and feed data exists for the SKU.
--   lead_time_days_override — highest-precedence lead time (plan D-4: override → vendor
--                             feed → configured default); NULL falls through to the feed.
--   preferred_source_type   — sourcing preference for F5 (stored only in F3; no reader yet).
--   snoozed_until           — policy suppressed from scan/event/needs evaluation while this
--                             instant is in the future (Odoo orderpoint snooze); NULL or a
--                             past instant means active evaluation.
--   active                  — hard on/off switch; inactive policies are never evaluated.
ALTER TABLE replenishment_policy
    ADD COLUMN order_multiple integer,
    ADD COLUMN lead_time_days_override integer,
    ADD COLUMN preferred_source_type character varying(30) NOT NULL DEFAULT 'EITHER',
    ADD COLUMN snoozed_until timestamp(6) with time zone,
    ADD COLUMN active boolean NOT NULL DEFAULT true;

ALTER TABLE replenishment_policy
    ADD CONSTRAINT replenishment_policy_order_multiple_check
        CHECK (order_multiple IS NULL OR order_multiple > 0),
    ADD CONSTRAINT replenishment_policy_lead_time_days_override_check
        CHECK (lead_time_days_override IS NULL OR lead_time_days_override >= 0),
    ADD CONSTRAINT replenishment_policy_preferred_source_type_check
        CHECK (preferred_source_type IN ('INTERNAL_TRANSFER', 'PURCHASE', 'EITHER'));

-- Stock-out deadline (Odoo orderpoint lead_days_date analogue): the earliest date the
-- horizon-bounded projection goes negative, computed at task creation/refresh for
-- prioritization. Nullable: pre-F3 tasks carry none, and a triggered policy whose
-- projection never dips below zero within the lead horizon has no deadline.
ALTER TABLE replenishment_task
    ADD COLUMN deadline_date date;

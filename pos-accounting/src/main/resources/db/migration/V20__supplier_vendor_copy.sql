-- CAP:550 S24 (#2517; SPEC-accounting-workspace §4.9 "Vendors (AW23)", §7.4; ADR-0044 R1, R3; ADR-0070 Decision 7;
-- ADR-0072; Security ruling #2617): one vendor key. Bills and AP payments name the pos-supplier vendor id, read from
-- a local copy of the vendor master that only the supplier.vendor.updated consumer writes.
--
-- 1. ext_supplier_vendor: the copy. Every vendor, in either status. tax_registrations holds the fact's
--    [{scheme, region, last4}] exactly as the schemaVersion 2 fact carries them; no column holds a full
--    registration number (ADR-0072, Security ruling #2617). Version 1 vendor facts are never applied.
-- 2. ap_vendor_settings: what stays accounting's (AW23): the remit-to confirmation and the vendor's AP defaults (AW39).
-- 3. supplier_invoice_hold: an EDI invoice fact that cannot become a bill yet (no vendorId, or a vendor not yet
--    copied), held with its payload and written with the processed mark, so the fact is never dropped.
-- 4. vendor_bill.approved_remit_to_version: the copy's remit-to version when the bill was approved; never cleared.
-- 5. ap_vendor and its first-sight sync are retired. Pre-production: alpha's AP data is reseeded, no vendor id is
--    rewritten (ADR-0070 Consequences).
--
-- Tenancy: every new table carries tenant_id with row-level security (ADR-0062). No timestamp default (ADR-0024).

CREATE TABLE public.ext_supplier_vendor (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    vendor_id uuid NOT NULL,
    vendor_number character varying(50) NOT NULL,
    display_name character varying(200) NOT NULL,
    status character varying(20) NOT NULL,
    status_changed_at timestamp(6) with time zone,
    remit_to jsonb,
    remit_to_version integer NOT NULL,
    remit_to_changed_at timestamp(6) with time zone,
    remit_to_requested_by character varying(255),
    remit_to_approved_by character varying(255),
    default_payment_terms character varying(20),
    default_currency character varying(3),
    tax_registrations jsonb NOT NULL,
    created_by character varying(255) NOT NULL,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_supplier_vendor_status_check CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ext_supplier_vendor_remit_to_version_check CHECK (remit_to_version >= 0)
);

COMMENT ON TABLE public.ext_supplier_vendor IS
    'Copy of the pos-supplier vendor master (S24, AW23), written only by the supplier.vendor.updated consumer under '
    'ReplicaVersionGuard; facts below schemaVersion 2 are never applied (ADR-0072).';
COMMENT ON COLUMN public.ext_supplier_vendor.tax_registrations IS
    '[{scheme, region, last4}] as the schemaVersion 2 fact carries them. Never a full registration number '
    '(Security ruling #2617, ADR-0072). Read by no rule of S24.';

ALTER TABLE ONLY public.ext_supplier_vendor
    ADD CONSTRAINT ext_supplier_vendor_pkey PRIMARY KEY (vendor_id);
ALTER TABLE ONLY public.ext_supplier_vendor
    ADD CONSTRAINT ext_supplier_vendor_tenant_key UNIQUE (tenant_id, vendor_id);
CREATE INDEX ext_supplier_vendor_tenant_idx ON public.ext_supplier_vendor USING btree (tenant_id);
CREATE INDEX ext_supplier_vendor_name_idx ON public.ext_supplier_vendor USING btree (tenant_id, display_name);

ALTER TABLE public.ext_supplier_vendor ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ext_supplier_vendor FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.ext_supplier_vendor
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

CREATE TABLE public.ap_vendor_settings (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    ap_vendor_settings_id uuid NOT NULL,
    vendor_id uuid NOT NULL,
    confirmed_remit_to_version integer,
    remit_to_confirmed_by character varying(255),
    remit_to_confirmed_at timestamp(6) with time zone,
    remit_to_confirmation_justification character varying(1000),
    default_debit_class character varying(20),
    default_expense_mapping_key character varying(100),
    version bigint NOT NULL,
    CONSTRAINT ap_vendor_settings_debit_class_check CHECK (default_debit_class IN ('GOODS', 'EXPENSE'))
);

COMMENT ON TABLE public.ap_vendor_settings IS
    'What stays accounting''s about a pos-supplier vendor (S24, AW23): the remit-to confirmation and the AP defaults '
    'S12''s posting falls back to (AW39). Each change is an accounting_audit_log row (entity VENDOR).';

ALTER TABLE ONLY public.ap_vendor_settings
    ADD CONSTRAINT ap_vendor_settings_pkey PRIMARY KEY (ap_vendor_settings_id);
ALTER TABLE ONLY public.ap_vendor_settings
    ADD CONSTRAINT ap_vendor_settings_tenant_key UNIQUE (tenant_id, ap_vendor_settings_id);
ALTER TABLE ONLY public.ap_vendor_settings
    ADD CONSTRAINT ap_vendor_settings_tenant_vendor_key UNIQUE (tenant_id, vendor_id);
CREATE INDEX ap_vendor_settings_tenant_idx ON public.ap_vendor_settings USING btree (tenant_id);

ALTER TABLE public.ap_vendor_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ap_vendor_settings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.ap_vendor_settings
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

CREATE TABLE public.supplier_invoice_hold (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    hold_id uuid NOT NULL,
    event_id uuid NOT NULL,
    supplier_invoice_ref character varying(100) NOT NULL,
    vendor_id uuid,
    reason character varying(30) NOT NULL,
    payload jsonb NOT NULL,
    received_at timestamp(6) with time zone NOT NULL,
    released_at timestamp(6) with time zone,
    released_bill_id uuid,
    CONSTRAINT supplier_invoice_hold_reason_check CHECK (reason IN ('VENDOR_ID_MISSING', 'VENDOR_NOT_IN_COPY'))
);

COMMENT ON TABLE public.supplier_invoice_hold IS
    'An EDI invoice fact that cannot become a bill yet (S24): no vendorId (waits for S25) or a vendor not yet copied '
    '(released when the vendor is copied). Written with the processed mark, so the fact is never dropped.';

ALTER TABLE ONLY public.supplier_invoice_hold
    ADD CONSTRAINT supplier_invoice_hold_pkey PRIMARY KEY (hold_id);
ALTER TABLE ONLY public.supplier_invoice_hold
    ADD CONSTRAINT supplier_invoice_hold_tenant_key UNIQUE (tenant_id, hold_id);
-- One hold per fact: event ids are UUIDv7, unique on their own; the key leads with tenant_id like every other.
ALTER TABLE ONLY public.supplier_invoice_hold
    ADD CONSTRAINT supplier_invoice_hold_event_key UNIQUE (tenant_id, event_id);
CREATE INDEX supplier_invoice_hold_tenant_idx ON public.supplier_invoice_hold USING btree (tenant_id);
CREATE INDEX supplier_invoice_hold_open_vendor_idx ON public.supplier_invoice_hold USING btree (tenant_id, vendor_id)
    WHERE released_at IS NULL;

ALTER TABLE public.supplier_invoice_hold ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.supplier_invoice_hold FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.supplier_invoice_hold
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

ALTER TABLE public.vendor_bill ADD COLUMN approved_remit_to_version integer;

COMMENT ON COLUMN public.vendor_bill.approved_remit_to_version IS
    'The vendor''s remit-to version (ext_supplier_vendor) when the bill was approved, by a person or the system; never '
    'cleared, not even by a void. Payment re-checks it (S24, §4.9 "Paying checks the remit-to again").';

DROP TABLE public.ap_vendor;

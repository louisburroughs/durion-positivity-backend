-- durion-positivity-backend#2516 (CAP:550 S23; SPEC-accounting-workspace §4.9 "Vendors (AW23)"; ADR-0070
-- Decisions 2 and 7): the vendor master. One supplier_vendor row per party the shop buys from or pays, with
-- or without a supplier connection; every connection profile belongs to exactly one vendor.
--
-- 1. supplier_vendor: the aggregate. vendor_number is the tenant-unique, immutable reference people quote
--    (ADR-0064) and YAML profiles bind to. remit_to is the APPROVED remit-to only (a pending change lives in
--    supplier_vendor_remit_change until a second person approves it); remit_to_version is 0 with none, 1 when
--    given at creation, +1 per approved change. tax_registrations and remit_to are jsonb. No bank details
--    (OI-14). default_payment_terms / default_currency are nullable only for the vendors this migration
--    backfills: the API requires both on create and update.
-- 2. supplier_vendor_remit_change: remit-to change requests. At most one PENDING per vendor (partial unique
--    index); APPROVED and REJECTED are terminal. Requester, decider, reason and notes are kept permanently.
-- 3. supplier_vendor_number_sequence: one counter row per tenant for allocated numbers V-000001, V-000002, ...
--    (the pos-workorder document_number_sequence pattern, #2150).
-- 4. supplier_profile.vendor_id: backfilled with one ACTIVE vendor per existing profile, in the profile's
--    tenant, then NOT NULL with a composite foreign key. The backfilled vendor takes the profile's own id
--    as its vendor_id (a UUIDv7 already, and it makes the profile-to-vendor link a plain copy). Its number is
--    upper(supplier_ref) with characters outside [A-Z0-9-] replaced by '-', cut to 30; a number that would
--    not start with a letter or digit is prefixed with 'V', and a second profile of one tenant whose ref
--    folds to the same number gets a '-<n>' suffix so the per-tenant unique key holds. Exchange-audit,
--    invoice and transmission rows reference the profile, not the vendor, so they stay attached untouched.
-- 5. supplier_invoice.vendor_id: the fetching profile's vendor, set on every invoice imported from now on;
--    null for documents fetched before this migration (no backfill).
--
-- ADR-0062: every new table is tenant-scoped (tenant_id first, row-level security, tenant_isolation policy,
-- tenant index, (tenant_id, pk) key; every unique key leads with tenant_id; foreign keys are composite).
-- None is global, so db/tenancy-global-tables.txt is unchanged. The backfill lifts FORCE ROW LEVEL SECURITY
-- for its own statements and restores it, as pos-accounting V8 does: the owner running Flyway is otherwise
-- bound to one tenant (or none) and would see only that tenant's profiles. The migration is one
-- transaction, so nobody observes the tables without it.

CREATE TABLE public.supplier_vendor (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    vendor_id uuid NOT NULL,
    vendor_number character varying(30) NOT NULL,
    legal_name character varying(255) NOT NULL,
    display_name character varying(255) NOT NULL,
    tax_registrations jsonb DEFAULT '[]'::jsonb NOT NULL,
    remit_to jsonb,
    remit_to_version integer DEFAULT 0 NOT NULL,
    remit_to_changed_at timestamp(6) with time zone,
    remit_to_requested_by character varying(255),
    remit_to_approved_by character varying(255),
    default_payment_terms character varying(16),
    default_currency character varying(3),
    status character varying(16) NOT NULL,
    status_changed_at timestamp(6) with time zone,
    status_reason character varying(500),
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    created_by character varying(255) NOT NULL,
    updated_by character varying(255),
    version bigint NOT NULL,
    CONSTRAINT chk_svendor_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying])::text[]))),
    CONSTRAINT chk_svendor_number CHECK (((vendor_number)::text ~ '^[A-Z0-9][A-Z0-9-]{0,29}$'::text)),
    CONSTRAINT chk_svendor_remit_to_version CHECK ((remit_to_version >= 0)),
    CONSTRAINT chk_svendor_remit_to_present CHECK (((remit_to IS NULL) = (remit_to_version = 0))),
    CONSTRAINT chk_svendor_payment_terms CHECK (((default_payment_terms IS NULL) OR ((default_payment_terms)::text ~ '^(DUE_ON_RECEIPT|NET([1-9]|[1-9][0-9]|1[01][0-9]|120))$'::text))),
    CONSTRAINT chk_svendor_currency CHECK (((default_currency IS NULL) OR ((default_currency)::text ~ '^[A-Z]{3}$'::text)))
);

COMMENT ON TABLE public.supplier_vendor IS
    'Vendor master (#2516, ADR-0070 Decision 2): one row per party the shop buys from or pays. Never deleted; '
    'deactivated with status INACTIVE. remit_to is the approved remit-to only. No bank details (OI-14).';

ALTER TABLE ONLY public.supplier_vendor
    ADD CONSTRAINT supplier_vendor_pkey PRIMARY KEY (vendor_id);

ALTER TABLE ONLY public.supplier_vendor
    ADD CONSTRAINT supplier_vendor_tenant_key UNIQUE (tenant_id, vendor_id);

ALTER TABLE ONLY public.supplier_vendor
    ADD CONSTRAINT supplier_vendor_number_key UNIQUE (tenant_id, vendor_number);

CREATE INDEX supplier_vendor_tenant_idx ON public.supplier_vendor USING btree (tenant_id);

ALTER TABLE public.supplier_vendor ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.supplier_vendor FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.supplier_vendor
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

CREATE TABLE public.supplier_vendor_remit_change (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    change_id uuid NOT NULL,
    vendor_id uuid NOT NULL,
    proposed_remit_to jsonb NOT NULL,
    reason character varying(1000) NOT NULL,
    status character varying(16) NOT NULL,
    from_version integer NOT NULL,
    to_version integer,
    requested_by character varying(255) NOT NULL,
    requested_at timestamp(6) with time zone NOT NULL,
    decided_by character varying(255),
    decided_at timestamp(6) with time zone,
    decision_note character varying(1000),
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    CONSTRAINT chk_svremit_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying])::text[]))),
    CONSTRAINT chk_svremit_decided CHECK ((((status)::text = 'PENDING'::text) = (decided_at IS NULL))),
    CONSTRAINT chk_svremit_to_version CHECK ((((status)::text = 'APPROVED'::text) = (to_version IS NOT NULL)))
);

COMMENT ON TABLE public.supplier_vendor_remit_change IS
    'Remit-to change requests (#2516, SPEC §4.9 "Remit-to changes need a second person"): PENDING until '
    'someone other than the requester approves or rejects it. At most one PENDING per vendor.';

ALTER TABLE ONLY public.supplier_vendor_remit_change
    ADD CONSTRAINT supplier_vendor_remit_change_pkey PRIMARY KEY (change_id);

ALTER TABLE ONLY public.supplier_vendor_remit_change
    ADD CONSTRAINT supplier_vendor_remit_change_tenant_key UNIQUE (tenant_id, change_id);

ALTER TABLE ONLY public.supplier_vendor_remit_change
    ADD CONSTRAINT fk_svremit_vendor FOREIGN KEY (tenant_id, vendor_id)
        REFERENCES public.supplier_vendor(tenant_id, vendor_id);

CREATE UNIQUE INDEX ux_svremit_one_pending ON public.supplier_vendor_remit_change USING btree (tenant_id, vendor_id)
    WHERE ((status)::text = 'PENDING'::text);

CREATE INDEX idx_svremit_vendor_requested ON public.supplier_vendor_remit_change USING btree (tenant_id, vendor_id, requested_at);

CREATE INDEX supplier_vendor_remit_change_tenant_idx ON public.supplier_vendor_remit_change USING btree (tenant_id);

ALTER TABLE public.supplier_vendor_remit_change ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.supplier_vendor_remit_change FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.supplier_vendor_remit_change
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

CREATE TABLE public.supplier_vendor_number_sequence (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    id uuid NOT NULL,
    next_value bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT chk_svnumseq_next_value CHECK ((next_value > 0))
);

COMMENT ON TABLE public.supplier_vendor_number_sequence IS
    'Next allocated vendor number per tenant (#2516): read FOR UPDATE and advanced in the transaction that '
    'inserts the vendor, so two creates cannot pick the same V-nnnnnn.';

ALTER TABLE ONLY public.supplier_vendor_number_sequence
    ADD CONSTRAINT supplier_vendor_number_sequence_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.supplier_vendor_number_sequence
    ADD CONSTRAINT supplier_vendor_number_sequence_tenant_key UNIQUE (tenant_id, id);

ALTER TABLE ONLY public.supplier_vendor_number_sequence
    ADD CONSTRAINT supplier_vendor_number_sequence_one_per_tenant UNIQUE (tenant_id);

ALTER TABLE public.supplier_vendor_number_sequence ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.supplier_vendor_number_sequence FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.supplier_vendor_number_sequence
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

-- Backfill: one ACTIVE vendor per existing profile, in the profile's tenant.
ALTER TABLE public.supplier_profile ADD COLUMN vendor_id uuid;

ALTER TABLE public.supplier_profile NO FORCE ROW LEVEL SECURITY;
ALTER TABLE public.supplier_vendor NO FORCE ROW LEVEL SECURITY;

WITH folded AS (
    SELECT p.tenant_id,
           p.vendor_profile_id,
           p.display_name,
           CASE
               WHEN left(regexp_replace(upper(p.supplier_ref), '[^A-Z0-9-]', '-', 'g'), 30) ~ '^[A-Z0-9]'
                   THEN left(regexp_replace(upper(p.supplier_ref), '[^A-Z0-9-]', '-', 'g'), 30)
               ELSE left('V' || regexp_replace(upper(p.supplier_ref), '[^A-Z0-9-]', '-', 'g'), 30)
           END AS base_number
    FROM public.supplier_profile p
),
numbered AS (
    SELECT f.*,
           row_number() OVER (PARTITION BY f.tenant_id, f.base_number ORDER BY f.vendor_profile_id) AS rn
    FROM folded f
)
INSERT INTO public.supplier_vendor (tenant_id, vendor_id, vendor_number, legal_name, display_name,
                                    tax_registrations, remit_to, remit_to_version, status, created_at,
                                    updated_at, created_by, updated_by, version)
SELECT n.tenant_id,
       n.vendor_profile_id,
       CASE
           WHEN n.rn = 1 THEN n.base_number
           ELSE left(n.base_number, 30 - length(n.rn::text) - 1) || '-' || n.rn::text
       END,
       n.display_name,
       n.display_name,
       '[]'::jsonb,
       NULL,
       0,
       'ACTIVE',
       now(),
       now(),
       'system:flyway-v3',
       'system:flyway-v3',
       0
FROM numbered n;

UPDATE public.supplier_profile SET vendor_id = vendor_profile_id;

ALTER TABLE public.supplier_vendor FORCE ROW LEVEL SECURITY;
ALTER TABLE public.supplier_profile FORCE ROW LEVEL SECURITY;

ALTER TABLE public.supplier_profile ALTER COLUMN vendor_id SET NOT NULL;

ALTER TABLE ONLY public.supplier_profile
    ADD CONSTRAINT fk_sprofile_vendor FOREIGN KEY (tenant_id, vendor_id)
        REFERENCES public.supplier_vendor(tenant_id, vendor_id);

CREATE INDEX idx_sprofile_vendor ON public.supplier_profile USING btree (tenant_id, vendor_id);

COMMENT ON COLUMN public.supplier_profile.vendor_id IS
    'The vendor this connection profile belongs to (#2516, ADR-0070 Decision 7). Required; YAML profiles '
    'bind by vendor_number.';

-- The fetching profile's vendor on every invoice imported from now on; null for earlier documents.
ALTER TABLE public.supplier_invoice ADD COLUMN vendor_id uuid;

ALTER TABLE ONLY public.supplier_invoice
    ADD CONSTRAINT fk_sinvoice_vendor FOREIGN KEY (tenant_id, vendor_id)
        REFERENCES public.supplier_vendor(tenant_id, vendor_id);

COMMENT ON COLUMN public.supplier_invoice.vendor_id IS
    'The fetching profile''s vendor at import (#2516); null for documents fetched before the vendor master.';

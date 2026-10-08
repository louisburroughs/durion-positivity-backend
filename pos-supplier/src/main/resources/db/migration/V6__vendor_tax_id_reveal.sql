-- durion-positivity-backend#2621 (CAP:550; Security ruling on #2617, ruling 4): the reveal audit of vendor
-- tax-registration numbers. One row per reveal through POST /v1/supplier/vendors/{vendorId}/tax-registrations/
-- {registrationId}/reveal, written in the reveal's own transaction BEFORE the number is returned (no row, no
-- number), including a reveal whose ciphertext could not be decrypted (outcome UNREADABLE, reason NULL) and one refused
-- because its reason contained the number (outcome REASON_REJECTED, reason NULL: that reason held the
-- number; Security confirmation on louisburroughs/durion#571). A 403 or 404 writes nothing. Read through GET /v1/supplier/vendors/{vendorId}/tax-id-reveals (supplier:audit:read).
--
-- The row never holds the number or last4. Append-only: the code never updates or deletes a row, and the
-- application role loses UPDATE and DELETE on the table below. No foreign key to supplier_vendor: the record
-- outlives what it names (the supplier_audit_access precedent).
--
-- ADR-0062: tenant-scoped (tenant_id first, row-level security enabled and forced, tenant_isolation policy,
-- tenant index, (tenant_id, pk) key). Not global, so db/tenancy-global-tables.txt is unchanged.

CREATE TABLE public.supplier_vendor_tax_id_reveal (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    reveal_id uuid NOT NULL,
    vendor_id uuid NOT NULL,
    registration_id uuid NOT NULL,
    scheme character varying(32) NOT NULL,
    revealed_by character varying(255) NOT NULL,
    revealed_by_roles character varying(1000) NOT NULL,
    reason character varying(500),
    correlation_id character varying(100),
    revealed_at timestamp(6) with time zone NOT NULL,
    outcome character varying(16) NOT NULL,
    CONSTRAINT chk_svtir_outcome CHECK (((outcome)::text = ANY ((ARRAY['REVEALED'::character varying, 'UNREADABLE'::character varying, 'REASON_REJECTED'::character varying])::text[]))),
    -- Security ruling on #2621 (2026-10-08): reason is NULL exactly on REASON_REJECTED (it held the number) and
    -- UNREADABLE (it could not be checked against a value that could not be read), and kept on REVEALED.
    CONSTRAINT chk_svtir_reason_presence CHECK ((((outcome)::text = ANY ((ARRAY['REASON_REJECTED'::character varying, 'UNREADABLE'::character varying])::text[])) = (reason IS NULL))),
    -- A kept reason is 10 to 500 characters (the column bounds 500), counted as Java counts them: code points.
    CONSTRAINT chk_svtir_reason CHECK (((reason IS NULL) OR (length(btrim((reason)::text)) >= 10)))
);

COMMENT ON TABLE public.supplier_vendor_tax_id_reveal IS
    'Reveal audit of vendor tax-registration numbers (#2621, VENDOR_TAX_ID_REVEALED): written before the number '
    'is returned; append-only; never holds the number or last4.';

ALTER TABLE ONLY public.supplier_vendor_tax_id_reveal
    ADD CONSTRAINT supplier_vendor_tax_id_reveal_pkey PRIMARY KEY (reveal_id);

ALTER TABLE ONLY public.supplier_vendor_tax_id_reveal
    ADD CONSTRAINT supplier_vendor_tax_id_reveal_tenant_key UNIQUE (tenant_id, reveal_id);

CREATE INDEX supplier_vendor_tax_id_reveal_tenant_idx ON public.supplier_vendor_tax_id_reveal USING btree (tenant_id);

CREATE INDEX idx_svtir_vendor_time ON public.supplier_vendor_tax_id_reveal
    USING btree (tenant_id, vendor_id, revealed_at DESC);

ALTER TABLE public.supplier_vendor_tax_id_reveal ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.supplier_vendor_tax_id_reveal FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.supplier_vendor_tax_id_reveal
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

-- Append-only for the application. pos_app is the shared non-owner role (postgres/init-tenancy.sh); its
-- default privileges grant UPDATE and DELETE on every new table, so they are taken back here. Guarded, because
-- a database without the role (a bare local Postgres) has nothing to revoke.
DO $revoke$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pos_app') THEN
        REVOKE UPDATE, DELETE, TRUNCATE ON public.supplier_vendor_tax_id_reveal FROM pos_app;
    END IF;
END
$revoke$;

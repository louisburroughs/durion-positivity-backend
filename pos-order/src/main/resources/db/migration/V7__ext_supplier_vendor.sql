-- CAP:550 S24 (#2517): pos-order's copy of pos-supplier's vendor master (ADR-0044 R3, ADR-0070 Decision 7).
--
-- Written only by the supplier.vendor.updated branch of SupplierOrderResultListener, under ReplicaVersionGuard.
-- It holds every vendor, active or inactive, and only the R3 minimum a purchase order needs: the vendor key, the
-- number people quote, the name screens show and the status the vendor guard reads. No tax registration, remit-to
-- or payment term is copied here.
CREATE TABLE public.ext_supplier_vendor (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    vendor_id uuid NOT NULL,
    vendor_number character varying(64) NOT NULL,
    display_name character varying(255) NOT NULL,
    status character varying(16) NOT NULL,
    status_changed_at timestamp with time zone,
    aggregate_version bigint NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT ext_supplier_vendor_pkey PRIMARY KEY (vendor_id),
    CONSTRAINT ext_supplier_vendor_tenant_key UNIQUE (tenant_id, vendor_id),
    CONSTRAINT ext_supplier_vendor_status_check CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX ext_supplier_vendor_tenant_idx ON public.ext_supplier_vendor USING btree (tenant_id);

ALTER TABLE public.ext_supplier_vendor ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ext_supplier_vendor FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.ext_supplier_vendor
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

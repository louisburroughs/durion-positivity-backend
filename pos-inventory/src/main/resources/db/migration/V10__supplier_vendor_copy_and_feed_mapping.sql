-- CAP:550 S36 (#2525): purchase suggestions name the pos-supplier vendor.
--
-- ext_supplier_vendor: pos-inventory's copy of pos-supplier's vendor master (ADR-0044 R3), written only by the
-- supplier.vendor.updated branch of SupplierEventsListener under a stale-version guard. It holds every vendor, active
-- or inactive, and only what a purchase suggestion shows and checks: the vendor key, its number, its display name and
-- its status.
CREATE TABLE public.ext_supplier_vendor (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    vendor_id uuid NOT NULL,
    vendor_number character varying(30),
    display_name character varying(255),
    status character varying(16),
    status_changed_at timestamp(6) with time zone,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone,
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

-- vendor_feed_mapping: which pos-supplier vendor a manufacturer or distributor feed vendor is. feed_vendor_ref is the
-- manufacturer id (a UUID, stored lower-case) or the distributor id (free text, as the distributor feed carries it).
-- vendor_id is a pos-supplier vendor id with no foreign key: the vendor lives in another service and is checked against
-- the ext_supplier_vendor copy by the service.
CREATE TABLE public.vendor_feed_mapping (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    mapping_id uuid NOT NULL,
    feed_type character varying(20) NOT NULL,
    feed_vendor_ref character varying(255) NOT NULL,
    vendor_id uuid NOT NULL,
    note character varying(500),
    created_by character varying(255),
    created_at timestamp(6) with time zone NOT NULL,
    updated_by character varying(255),
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT vendor_feed_mapping_pkey PRIMARY KEY (mapping_id),
    CONSTRAINT vendor_feed_mapping_tenant_key UNIQUE (tenant_id, mapping_id),
    CONSTRAINT uq_vendor_feed_mapping_feed UNIQUE (tenant_id, feed_type, feed_vendor_ref),
    CONSTRAINT vendor_feed_mapping_feed_type_check CHECK (feed_type IN ('MANUFACTURER', 'DISTRIBUTOR'))
);

CREATE INDEX idx_vendor_feed_mapping_vendor ON public.vendor_feed_mapping USING btree (tenant_id, vendor_id);

ALTER TABLE public.vendor_feed_mapping ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.vendor_feed_mapping FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.vendor_feed_mapping
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

-- purchase_suggestion: the feed's own vendor reference is kept as text (a distributor id need not be a UUID), and the
-- resolved pos-supplier vendor is stored beside it. converted_at is when the suggestion became a purchase order.
ALTER TABLE public.purchase_suggestion RENAME COLUMN vendor_ref_id TO feed_vendor_ref;
ALTER TABLE public.purchase_suggestion
    ALTER COLUMN feed_vendor_ref TYPE character varying(255) USING feed_vendor_ref::text;
ALTER TABLE public.purchase_suggestion ADD COLUMN vendor_id uuid;
ALTER TABLE public.purchase_suggestion ADD COLUMN converted_at timestamp(6) with time zone;

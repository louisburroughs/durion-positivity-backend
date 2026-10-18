-- #2518 (CAP:550 S25, Part A; ADR-0070 Decisions 1, 3, 4, 8, 9, 10; SPEC-accounting-workspace 4.8, 4.9, 7.4, 9.2): the
-- schema of bill intake. A bill is no longer born by an event alone: a bill_intake_item is the draft a person checks
-- (an upload, a spreadsheet import, or an EDI fact that names no vendor), and vendor_bill is only ever written by
-- BillIntakePort. Every table carries the full tenancy schema of TENANCY_SCHEMA.md "Adding a table": tenant_id
-- defaulting from app_current_tenant(), ENABLE + FORCE row-level security with the tenant_isolation policy, a
-- (tenant_id, id) key every foreign key targets, and uniques scoped by tenant.
--
-- Part A writes bill_intake_item only (EDI drafts). The file, import-row and mapping-template tables are created
-- here so the later parts (B: files and scan, D: imports and source files) need no second migration; nothing reads or
-- writes them yet.

-- 1. bill_source_file: the stored original of an upload or import, and the derived pages of it (page_number set,
--    derived_from the original). State is the file's own lifecycle, retention and legal hold are the file's too.
CREATE TABLE public.bill_source_file (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    file_id uuid NOT NULL,
    derived_from uuid,
    page_number integer,
    sha256 character varying(64) NOT NULL,
    content_type character varying(100) NOT NULL,
    size_bytes bigint NOT NULL,
    page_count integer,
    original_name character varying(255),
    state character varying(16) NOT NULL,
    retention_until timestamp(6) with time zone,
    legal_hold boolean DEFAULT false NOT NULL,
    legal_hold_reason character varying(1000),
    created_by character varying(50) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    purged_at timestamp(6) with time zone,
    CONSTRAINT bill_source_file_state_check CHECK (state IN ('QUARANTINED', 'STORED', 'REJECTED', 'PURGED')),
    CONSTRAINT bill_source_file_page_check CHECK (page_number IS NULL OR page_number >= 1),
    CONSTRAINT bill_source_file_page_count_check CHECK (page_count IS NULL OR page_count >= 1)
);
COMMENT ON TABLE public.bill_source_file IS
    'The original file of a bill intake, or a derived page of it (derived_from set). Metadata outlives the bytes: a '
    'purge drops bill_source_file_content and keeps this row with purged_at (S25, ADR-0070 Decision 8).';
ALTER TABLE ONLY public.bill_source_file
    ADD CONSTRAINT bill_source_file_pkey PRIMARY KEY (file_id);
ALTER TABLE ONLY public.bill_source_file
    ADD CONSTRAINT bill_source_file_tenant_key UNIQUE (tenant_id, file_id);
ALTER TABLE ONLY public.bill_source_file
    ADD CONSTRAINT bill_source_file_derived_fk FOREIGN KEY (tenant_id, derived_from)
        REFERENCES public.bill_source_file (tenant_id, file_id);
CREATE INDEX bill_source_file_tenant_idx ON public.bill_source_file USING btree (tenant_id);
CREATE INDEX bill_source_file_derived_idx ON public.bill_source_file USING btree (tenant_id, derived_from)
    WHERE derived_from IS NOT NULL;
CREATE INDEX bill_source_file_retention_idx ON public.bill_source_file USING btree (tenant_id, retention_until)
    WHERE state <> 'PURGED' AND retention_until IS NOT NULL;
ALTER TABLE public.bill_source_file ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.bill_source_file FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.bill_source_file
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

-- 2. bill_source_file_content: the bytes, apart from the metadata so a purge deletes this row and keeps the other.
CREATE TABLE public.bill_source_file_content (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    file_id uuid NOT NULL,
    content bytea NOT NULL
);
ALTER TABLE ONLY public.bill_source_file_content
    ADD CONSTRAINT bill_source_file_content_pkey PRIMARY KEY (file_id);
ALTER TABLE ONLY public.bill_source_file_content
    ADD CONSTRAINT bill_source_file_content_tenant_key UNIQUE (tenant_id, file_id);
ALTER TABLE ONLY public.bill_source_file_content
    ADD CONSTRAINT bill_source_file_content_file_fk FOREIGN KEY (tenant_id, file_id)
        REFERENCES public.bill_source_file (tenant_id, file_id);
CREATE INDEX bill_source_file_content_tenant_idx ON public.bill_source_file_content USING btree (tenant_id);
ALTER TABLE public.bill_source_file_content ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.bill_source_file_content FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.bill_source_file_content
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

-- 3. bill_intake_item: the draft. read_back is the field-by-field reading ({value, state} per field);
--    possible_duplicates and row_errors are what the checks found. confirmed_bill_id is set once, at confirmation.
CREATE TABLE public.bill_intake_item (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    intake_id uuid NOT NULL,
    intake_reference character varying(32) NOT NULL,
    channel character varying(10) NOT NULL,
    status character varying(20) NOT NULL,
    source_file_id uuid,
    split_of uuid,
    row_from integer,
    row_to integer,
    mapping_required boolean DEFAULT false NOT NULL,
    source_columns jsonb,
    rejection_reason character varying(40),
    extraction_outcome character varying(20),
    extraction_reason text,
    extraction_provider text,
    vendor_resolution character varying(20),
    read_back jsonb,
    row_errors jsonb,
    possible_duplicates jsonb,
    confirmed_bill_id uuid,
    uploaded_by character varying(50) NOT NULL,
    uploaded_at timestamp(6) with time zone NOT NULL,
    confirmed_by character varying(50),
    confirmed_at timestamp(6) with time zone,
    discarded_by character varying(50),
    discarded_at timestamp(6) with time zone,
    discard_reason character varying(1000),
    request_id uuid,
    last_extraction_failed boolean DEFAULT false NOT NULL,
    edited boolean DEFAULT false NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp(6) with time zone,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT bill_intake_item_channel_check CHECK (channel IN ('UPLOAD', 'IMPORT', 'EDI', 'EMAIL_IN')),
    CONSTRAINT bill_intake_item_status_check CHECK (status IN
        ('QUARANTINED', 'FILE_REJECTED', 'EXTRACTING', 'READY_FOR_REVIEW', 'CONFIRMED', 'DISCARDED')),
    CONSTRAINT bill_intake_item_outcome_check CHECK (extraction_outcome IS NULL OR extraction_outcome IN ('MANUAL', 'EXTRACTED')),
    CONSTRAINT bill_intake_item_vendor_resolution_check CHECK (vendor_resolution IS NULL OR vendor_resolution IN
        ('READ', 'CANDIDATES', 'NOT_SET_UP', 'RESOLVED')),
    CONSTRAINT bill_intake_item_rows_check CHECK (row_from IS NULL OR (row_to IS NOT NULL AND row_to >= row_from))
);
COMMENT ON TABLE public.bill_intake_item IS
    'A bill draft a person checks before a bill exists (S25; ADR-0070 Decision 1). Channels: UPLOAD, IMPORT, and EDI '
    '(a supplier invoice fact that names no vendor). No AP query reads a draft; BillIntakePort turns a confirmed one '
    'into a vendor_bill, the only writer of new bills.';
ALTER TABLE ONLY public.bill_intake_item
    ADD CONSTRAINT bill_intake_item_pkey PRIMARY KEY (intake_id);
ALTER TABLE ONLY public.bill_intake_item
    ADD CONSTRAINT bill_intake_item_tenant_key UNIQUE (tenant_id, intake_id);
ALTER TABLE ONLY public.bill_intake_item
    ADD CONSTRAINT bill_intake_item_reference_key UNIQUE (tenant_id, intake_reference);
ALTER TABLE ONLY public.bill_intake_item
    ADD CONSTRAINT bill_intake_item_source_file_fk FOREIGN KEY (tenant_id, source_file_id)
        REFERENCES public.bill_source_file (tenant_id, file_id);
ALTER TABLE ONLY public.bill_intake_item
    ADD CONSTRAINT bill_intake_item_split_fk FOREIGN KEY (tenant_id, split_of)
        REFERENCES public.bill_intake_item (tenant_id, intake_id);
ALTER TABLE ONLY public.bill_intake_item
    ADD CONSTRAINT bill_intake_item_bill_fk FOREIGN KEY (tenant_id, confirmed_bill_id)
        REFERENCES public.vendor_bill (tenant_id, vendor_bill_id);
-- One draft per bill, and one bill per draft.
CREATE UNIQUE INDEX bill_intake_item_confirmed_bill_key ON public.bill_intake_item USING btree (tenant_id, confirmed_bill_id)
    WHERE confirmed_bill_id IS NOT NULL;
-- A replayed requestId finds the first response: unique per tenant and channel.
CREATE UNIQUE INDEX bill_intake_item_request_key ON public.bill_intake_item USING btree (tenant_id, channel, request_id)
    WHERE request_id IS NOT NULL;
CREATE INDEX bill_intake_item_tenant_idx ON public.bill_intake_item USING btree (tenant_id);
CREATE INDEX bill_intake_item_status_idx ON public.bill_intake_item USING btree (tenant_id, status, uploaded_at);
CREATE INDEX bill_intake_item_source_file_idx ON public.bill_intake_item USING btree (tenant_id, source_file_id)
    WHERE source_file_id IS NOT NULL;
ALTER TABLE public.bill_intake_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.bill_intake_item FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.bill_intake_item
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

-- 4. bill_intake_import_row: the text cells of one row of an imported spreadsheet (the seam tabulates, this module
--    groups rows into drafts). Cells are text only.
CREATE TABLE public.bill_intake_import_row (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    import_row_id uuid NOT NULL,
    intake_id uuid NOT NULL,
    row_number integer NOT NULL,
    cells jsonb NOT NULL,
    CONSTRAINT bill_intake_import_row_number_check CHECK (row_number >= 1)
);
ALTER TABLE ONLY public.bill_intake_import_row
    ADD CONSTRAINT bill_intake_import_row_pkey PRIMARY KEY (import_row_id);
ALTER TABLE ONLY public.bill_intake_import_row
    ADD CONSTRAINT bill_intake_import_row_tenant_key UNIQUE (tenant_id, import_row_id);
ALTER TABLE ONLY public.bill_intake_import_row
    ADD CONSTRAINT bill_intake_import_row_position_key UNIQUE (tenant_id, intake_id, row_number);
ALTER TABLE ONLY public.bill_intake_import_row
    ADD CONSTRAINT bill_intake_import_row_item_fk FOREIGN KEY (tenant_id, intake_id)
        REFERENCES public.bill_intake_item (tenant_id, intake_id);
CREATE INDEX bill_intake_import_row_tenant_idx ON public.bill_intake_import_row USING btree (tenant_id);
ALTER TABLE public.bill_intake_import_row ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.bill_intake_import_row FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.bill_intake_import_row
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

-- 5. bill_intake_mapping_template: a saved column mapping, applied again when a file's header signature matches.
CREATE TABLE public.bill_intake_mapping_template (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    template_id uuid NOT NULL,
    name character varying(100) NOT NULL,
    columns jsonb NOT NULL,
    header_signature character varying(64) NOT NULL,
    created_by character varying(50) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL
);
ALTER TABLE ONLY public.bill_intake_mapping_template
    ADD CONSTRAINT bill_intake_mapping_template_pkey PRIMARY KEY (template_id);
ALTER TABLE ONLY public.bill_intake_mapping_template
    ADD CONSTRAINT bill_intake_mapping_template_tenant_key UNIQUE (tenant_id, template_id);
ALTER TABLE ONLY public.bill_intake_mapping_template
    ADD CONSTRAINT bill_intake_mapping_template_name_key UNIQUE (tenant_id, name);
CREATE INDEX bill_intake_mapping_template_tenant_idx ON public.bill_intake_mapping_template USING btree (tenant_id);
CREATE INDEX bill_intake_mapping_template_signature_idx
    ON public.bill_intake_mapping_template USING btree (tenant_id, header_signature);
ALTER TABLE public.bill_intake_mapping_template ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.bill_intake_mapping_template FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.bill_intake_mapping_template
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

-- 6. vendor_bill: where a bill came from, and the delivery reference of ruling 8 (G16). A bill with no intake (a
--    goods-receipt bill, an EDI bill with a vendor) holds null in all but delivery_reference.
ALTER TABLE public.vendor_bill ADD COLUMN intake_id uuid;
ALTER TABLE public.vendor_bill ADD COLUMN intake_channel character varying(10);
ALTER TABLE public.vendor_bill ADD COLUMN source_file_id uuid;
ALTER TABLE public.vendor_bill ADD COLUMN extraction_outcome character varying(20);
ALTER TABLE public.vendor_bill ADD COLUMN purchase_order_number_key character varying(255);
ALTER TABLE public.vendor_bill ADD COLUMN delivery_reference character varying(100);
ALTER TABLE public.vendor_bill ADD COLUMN delivery_reference_key character varying(255);
ALTER TABLE public.vendor_bill
    ADD CONSTRAINT vendor_bill_intake_channel_check CHECK (intake_channel IS NULL OR intake_channel IN ('UPLOAD', 'IMPORT', 'EDI', 'EMAIL_IN'));
ALTER TABLE public.vendor_bill
    ADD CONSTRAINT vendor_bill_extraction_outcome_check CHECK (extraction_outcome IS NULL OR extraction_outcome IN ('MANUAL', 'EXTRACTED'));
ALTER TABLE ONLY public.vendor_bill
    ADD CONSTRAINT vendor_bill_intake_fk FOREIGN KEY (tenant_id, intake_id)
        REFERENCES public.bill_intake_item (tenant_id, intake_id);
ALTER TABLE ONLY public.vendor_bill
    ADD CONSTRAINT vendor_bill_source_file_fk FOREIGN KEY (tenant_id, source_file_id)
        REFERENCES public.bill_source_file (tenant_id, file_id);
CREATE INDEX vendor_bill_intake_idx ON public.vendor_bill USING btree (tenant_id, intake_id) WHERE intake_id IS NOT NULL;
-- The key of the purchase-order number, the same normalisation as bill_number_key (ruling 8): backfilled once here,
-- written afterwards only by VendorBill#setPurchaseOrderNumber. SQL twin of VendorBillNumbers.normalise (see V4).
UPDATE public.vendor_bill
SET purchase_order_number_key = left(
        regexp_replace(
            regexp_replace(upper(normalize(purchase_order_number, NFKC)), '[^[:alnum:]]', '', 'g'),
            '^0+(?=.)',
            ''),
        255)
WHERE purchase_order_number IS NOT NULL;
COMMENT ON COLUMN public.vendor_bill.delivery_reference IS
    'The vendor''s delivery-note or ASN number, as stated (ruling 8 of #2518); optional. delivery_reference_key is its '
    'normalisation, written only by VendorBill#setDeliveryReference, the same normaliser as bill_number_key.';
-- The G16 look-up: a bill that names a delivery against a purchase order is matched against the goods-receipt bill
-- of the same vendor, purchase order and delivery. Not unique: the check is in code, and a vendor+PO-only match is a
-- warning, not a refusal.
CREATE INDEX vendor_bill_delivery_reference_idx
    ON public.vendor_bill USING btree (tenant_id, vendor_id, purchase_order_number_key, delivery_reference_key)
    WHERE delivery_reference_key IS NOT NULL
      AND purchase_order_number_key IS NOT NULL
      AND status NOT IN ('VOIDED', 'REJECTED')
      AND origin_event_type = 'GOODS_RECEIVED';

-- 7. supplier_invoice_hold: a VENDOR_ID_MISSING hold converted to a draft is released with no bill; it names the draft.
ALTER TABLE public.supplier_invoice_hold ADD COLUMN released_intake_id uuid;
COMMENT ON COLUMN public.supplier_invoice_hold.released_intake_id IS
    'The bill_intake_item a VENDOR_ID_MISSING hold was converted into (S25); released_bill_id stays null for it. '
    'VENDOR_ID_MISSING is retired as a reason: the conversion runs once and no new hold takes it.';

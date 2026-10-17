-- #2678 (CAP:550): an idempotency key on vendor create. When the create commits but the client sees a timeout, a
-- retry with the same requestId and the same payload answers the original 201 body instead of creating a second
-- vendor master record; the same requestId with another payload is 409 IDEMPOTENCY_CONFLICT (ADR-0017). One row per
-- tenant and requestId, written in the create's transaction; its unique key is the backstop for one requestId racing
-- itself.
CREATE TABLE public.supplier_vendor_create_request (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    request_id uuid NOT NULL,
    request_hash character varying(64) NOT NULL,
    vendor_id uuid NOT NULL,
    response_json text NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL
);

COMMENT ON TABLE public.supplier_vendor_create_request IS
    'Idempotency keys of vendor creates (#2678): the payload hash and the 201 body a requestId answered, '
    'so a retry after an unknown outcome replays the original instead of creating a duplicate vendor.';

ALTER TABLE ONLY public.supplier_vendor_create_request
    ADD CONSTRAINT supplier_vendor_create_request_pkey PRIMARY KEY (request_id);

ALTER TABLE ONLY public.supplier_vendor_create_request
    ADD CONSTRAINT supplier_vendor_create_request_tenant_key UNIQUE (tenant_id, request_id);

ALTER TABLE ONLY public.supplier_vendor_create_request
    ADD CONSTRAINT fk_svcreate_vendor FOREIGN KEY (tenant_id, vendor_id)
        REFERENCES public.supplier_vendor(tenant_id, vendor_id);

CREATE INDEX supplier_vendor_create_request_tenant_idx ON public.supplier_vendor_create_request USING btree (tenant_id);

ALTER TABLE public.supplier_vendor_create_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.supplier_vendor_create_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.supplier_vendor_create_request
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

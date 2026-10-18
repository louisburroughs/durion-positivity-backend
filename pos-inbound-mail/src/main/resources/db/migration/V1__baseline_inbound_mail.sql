-- pos-inbound-mail: Flyway baseline, written with the ADR-0062 tenancy schema from the start
-- (../durion/docs/architecture/deployment/TENANCY_SCHEMA.md, "Adding a table"). Global (unscoped)
-- tables: src/main/resources/db/tenancy-global-tables.txt.
--
-- The edge holds operating state only (CAP:550 S27 ruling 2): the address registry, one record per
-- received message, rate-limit windows and staged attachments. No business data, no message body.
--
-- Tenant context: app_current_tenant() reads the session setting app.current_tenant, which the
-- TenantAwareDataSource (pos-tenancy-common) binds per checkout. With nothing bound it is NULL:
-- every scoped table reads as empty and refuses inserts (fail closed).

SET check_function_bodies = false;

CREATE OR REPLACE FUNCTION public.app_current_tenant() RETURNS uuid
    LANGUAGE sql STABLE PARALLEL SAFE
    AS $$ SELECT NULLIF(current_setting('app.current_tenant', true), '')::uuid $$;

-- The address registry. GLOBAL on purpose: the receive path resolves a recipient address to its tenant
-- before any tenant is bound, so this table has no policy and carries tenant_id as plain data. The
-- address is an unguessable capability (bills- plus 26 base32 characters, ruling 8): it is never copied
-- to Kafka and never logged, only address_id is.
CREATE TABLE public.inbound_address (
    address_id uuid NOT NULL,
    address character varying(128) NOT NULL,
    tenant_id uuid NOT NULL,
    status character varying(8) NOT NULL,
    issued_by_request_id uuid,
    created_at timestamp(6) with time zone NOT NULL,
    retired_at timestamp(6) with time zone,
    CONSTRAINT inbound_address_status_chk CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'RETIRED'::character varying])::text[]))),
    CONSTRAINT inbound_address_lower_chk CHECK (((address)::text = lower((address)::text))),
    CONSTRAINT inbound_address_retired_chk CHECK ((((status)::text = 'ACTIVE'::text) = (retired_at IS NULL)))
);

COMMENT ON COLUMN public.inbound_address.tenant_id IS
    'ADR-0062: owning tenant, carried as data (global table, no policy); the receive path reads it with no tenant bound.';
COMMENT ON COLUMN public.inbound_address.issued_by_request_id IS
    'pos-accounting requestId of the rotation that issued this address; a replayed rotation returns it (ruling 8). NULL for an address issued by POST /addresses.';

-- One record per message the edge has seen for a tenant: the idempotency key (message_key, the lower-case hex
-- sha256 of the normalised Message-ID, or of the raw message when it has none), the verdict and the
-- outcome. Sender address is CONFIDENTIAL (ADR-0072 Decision 1): never logged, never a metric tag.
CREATE TABLE public.inbound_message (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    inbound_message_id uuid NOT NULL,
    address_id uuid NOT NULL,
    message_key character varying(64) NOT NULL,
    ses_message_id character varying(128),
    received_at timestamp(6) with time zone NOT NULL,
    from_address character varying(320),
    status character varying(16) NOT NULL,
    reason character varying(64),
    rate_limit_window_id uuid,
    CONSTRAINT inbound_message_key_chk CHECK (((message_key)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT inbound_message_status_chk CHECK (((status)::text = ANY ((ARRAY['RECEIVED'::character varying, 'REJECTED'::character varying, 'STAGED'::character varying, 'COLLECTED'::character varying, 'EXPIRED'::character varying])::text[])))
);

-- Fixed clock-hour windows per address (ruling 5): admitted and refused counts, reported once closed.
CREATE TABLE public.rate_limit_window (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    window_id uuid NOT NULL,
    address_id uuid NOT NULL,
    window_start timestamp(6) with time zone NOT NULL,
    admitted_count integer DEFAULT 0 NOT NULL,
    limited_count integer DEFAULT 0 NOT NULL,
    reported_at timestamp(6) with time zone,
    CONSTRAINT rate_limit_window_counts_chk CHECK (((admitted_count >= 0) AND (limited_count >= 0)))
);

-- One accepted attachment awaiting collection by pos-accounting. ref is the UUIDv7 the command carries;
-- file_name is CONFIDENTIAL text and never a key or a path. The bytes are in staged_attachment_content.
CREATE TABLE public.staged_attachment (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    ref uuid NOT NULL,
    inbound_message_id uuid NOT NULL,
    file_name text NOT NULL,
    declared_type character varying(255) NOT NULL,
    size_bytes bigint NOT NULL,
    sha256 character varying(64) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    collected_at timestamp(6) with time zone,
    CONSTRAINT staged_attachment_size_chk CHECK ((size_bytes >= 0))
);

-- The staging medium (ruling 3): bytes in the edge's own database under the same RLS as the metadata, so
-- another tenant's ref reads as absent. Behind the AttachmentStaging port; an object-store adapter can
-- replace it without changing the contract.
CREATE TABLE public.staged_attachment_content (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    ref uuid NOT NULL,
    content bytea NOT NULL
);

CREATE TABLE public.event_outbox (
    id uuid NOT NULL,
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    topic character varying(255) NOT NULL,
    record_key character varying(255) NOT NULL,
    payload text NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    published_at timestamp(6) with time zone,
    attempts integer DEFAULT 0 NOT NULL,
    last_error text
);

COMMENT ON COLUMN public.event_outbox.tenant_id IS
    'ADR-0062: producing tenant, carried as data (global table, no policy); stamped on the Kafka record header.';

CREATE TABLE public.processed_events (
    event_id character varying(64) NOT NULL,
    tenant_id uuid,
    owner character varying(64) NOT NULL,
    processed_at timestamp(6) with time zone NOT NULL
);

ALTER TABLE ONLY public.inbound_address
    ADD CONSTRAINT inbound_address_pkey PRIMARY KEY (address_id);

ALTER TABLE ONLY public.inbound_address
    ADD CONSTRAINT inbound_address_address_key UNIQUE (address);

ALTER TABLE ONLY public.inbound_address
    ADD CONSTRAINT inbound_address_tenant_key UNIQUE (tenant_id, address_id);

ALTER TABLE ONLY public.inbound_address
    ADD CONSTRAINT inbound_address_request_key UNIQUE (tenant_id, issued_by_request_id);

-- One ACTIVE address per tenant: concurrent first requests resolve here, the loser re-reads.
CREATE UNIQUE INDEX inbound_address_one_active_idx ON public.inbound_address USING btree (tenant_id) WHERE ((status)::text = 'ACTIVE'::text);

ALTER TABLE ONLY public.inbound_message
    ADD CONSTRAINT inbound_message_pkey PRIMARY KEY (inbound_message_id);

ALTER TABLE ONLY public.inbound_message
    ADD CONSTRAINT inbound_message_tenant_key UNIQUE (tenant_id, inbound_message_id);

ALTER TABLE ONLY public.inbound_message
    ADD CONSTRAINT inbound_message_key_uk UNIQUE (tenant_id, message_key);

ALTER TABLE ONLY public.rate_limit_window
    ADD CONSTRAINT rate_limit_window_pkey PRIMARY KEY (window_id);

ALTER TABLE ONLY public.rate_limit_window
    ADD CONSTRAINT rate_limit_window_tenant_key UNIQUE (tenant_id, window_id);

ALTER TABLE ONLY public.rate_limit_window
    ADD CONSTRAINT rate_limit_window_uk UNIQUE (tenant_id, address_id, window_start);

ALTER TABLE ONLY public.staged_attachment
    ADD CONSTRAINT staged_attachment_pkey PRIMARY KEY (ref);

ALTER TABLE ONLY public.staged_attachment
    ADD CONSTRAINT staged_attachment_tenant_key UNIQUE (tenant_id, ref);

ALTER TABLE ONLY public.staged_attachment_content
    ADD CONSTRAINT staged_attachment_content_pkey PRIMARY KEY (ref);

ALTER TABLE ONLY public.staged_attachment_content
    ADD CONSTRAINT staged_attachment_content_tenant_key UNIQUE (tenant_id, ref);

ALTER TABLE ONLY public.event_outbox
    ADD CONSTRAINT event_outbox_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.processed_events
    ADD CONSTRAINT processed_events_pkey PRIMARY KEY (event_id);

CREATE INDEX idx_event_outbox_unpublished ON public.event_outbox USING btree (id) WHERE (published_at IS NULL);

CREATE INDEX idx_processed_events_owner_tenant ON public.processed_events USING btree (owner, tenant_id, event_id);

CREATE INDEX inbound_message_tenant_idx ON public.inbound_message USING btree (tenant_id);
CREATE INDEX inbound_message_received_idx ON public.inbound_message USING btree (received_at);
CREATE INDEX rate_limit_window_tenant_idx ON public.rate_limit_window USING btree (tenant_id);
CREATE INDEX rate_limit_window_unreported_idx ON public.rate_limit_window USING btree (window_start) WHERE (reported_at IS NULL);
CREATE INDEX staged_attachment_tenant_idx ON public.staged_attachment USING btree (tenant_id);
CREATE INDEX staged_attachment_message_idx ON public.staged_attachment USING btree (tenant_id, inbound_message_id);
CREATE INDEX staged_attachment_created_idx ON public.staged_attachment USING btree (created_at);
CREATE INDEX staged_attachment_content_tenant_idx ON public.staged_attachment_content USING btree (tenant_id);

-- Foreign keys: tenant-scoped (tenant_id, ...) everywhere, including to the global registry, so a row can never
-- name another tenant's address.
ALTER TABLE ONLY public.inbound_message
    ADD CONSTRAINT inbound_message_address_fk FOREIGN KEY (tenant_id, address_id) REFERENCES public.inbound_address(tenant_id, address_id);

ALTER TABLE ONLY public.inbound_message
    ADD CONSTRAINT inbound_message_window_fk FOREIGN KEY (tenant_id, rate_limit_window_id) REFERENCES public.rate_limit_window(tenant_id, window_id);

ALTER TABLE ONLY public.rate_limit_window
    ADD CONSTRAINT rate_limit_window_address_fk FOREIGN KEY (tenant_id, address_id) REFERENCES public.inbound_address(tenant_id, address_id);

ALTER TABLE ONLY public.staged_attachment
    ADD CONSTRAINT staged_attachment_message_fk FOREIGN KEY (tenant_id, inbound_message_id) REFERENCES public.inbound_message(tenant_id, inbound_message_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.staged_attachment_content
    ADD CONSTRAINT staged_attachment_content_fk FOREIGN KEY (tenant_id, ref) REFERENCES public.staged_attachment(tenant_id, ref) ON DELETE CASCADE;

-- Row-level security (ADR-0062 §2) on every tenant-scoped table.
ALTER TABLE public.inbound_message ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.inbound_message FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.inbound_message
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

ALTER TABLE public.rate_limit_window ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.rate_limit_window FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.rate_limit_window
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

ALTER TABLE public.staged_attachment ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.staged_attachment FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.staged_attachment
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

ALTER TABLE public.staged_attachment_content ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.staged_attachment_content FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.staged_attachment_content
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

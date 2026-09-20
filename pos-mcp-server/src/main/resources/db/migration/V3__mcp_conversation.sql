-- pos-mcp-server #2073: persisted assistant conversation history.
--
-- Both tables are tenant-scoped (ADR-0062, ../durion/docs/architecture/deployment/TENANCY_SCHEMA.md "What every tenant-scoped table
-- has"): tenant_id first with the app_current_tenant() default, row-level security forced, a
-- (tenant_id, id) unique key as the composite foreign-key target, and the scoped -> scoped foreign key
-- composite so a key check (which runs as the owner and bypasses RLS) can never be satisfied by
-- another tenant's row.
--
-- mcp_conversation.owner_user_id is the token subject (CurrentUserContext.userId). Every read and
-- write filters on it alongside tenant_id; an id owned by another subject answers 404, never 403.
-- title_user_set records that the owner renamed the conversation, after which the server stops
-- deriving the title from the first user message.
--
-- mcp_message.content is the raw user text / assistant markdown (memory hydration and the preview
-- read it without re-rendering blocks); blocks is the ChatBlock segmentation of that same text and
-- may be an empty array. updated_at is the ADR-0024 audit column (the turn itself is write-once;
-- #2075 feedback updates the row). origin separates turns the chat path persisted (CHAT) from turns a client
-- appended directly (CLIENT), so grading can exclude client-authored rows.

CREATE TABLE public.mcp_conversation (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    id uuid NOT NULL,
    owner_user_id uuid NOT NULL,
    title character varying(120) NOT NULL,
    title_user_set boolean DEFAULT false NOT NULL,
    preview character varying(200),
    pinned boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT mcp_conversation_pkey PRIMARY KEY (id),
    CONSTRAINT mcp_conversation_tenant_key UNIQUE (tenant_id, id)
);

CREATE TABLE public.mcp_message (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    role character varying(16) NOT NULL,
    origin character varying(16) NOT NULL,
    content text NOT NULL,
    blocks jsonb DEFAULT '[]'::jsonb NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT mcp_message_pkey PRIMARY KEY (id),
    CONSTRAINT mcp_message_tenant_key UNIQUE (tenant_id, id),
    CONSTRAINT mcp_message_role_check CHECK (role IN ('user', 'assistant')),
    CONSTRAINT mcp_message_origin_check CHECK (origin IN ('CHAT', 'CLIENT')),
    CONSTRAINT mcp_message_conversation_fkey FOREIGN KEY (tenant_id, conversation_id)
        REFERENCES public.mcp_conversation (tenant_id, id) ON DELETE CASCADE
);

-- History rail: the owner's conversations, pinned first then most recently touched. Leads with
-- tenant_id, so no separate tenant index is needed on mcp_conversation.
CREATE INDEX idx_mcp_conversation_owner_rail
    ON public.mcp_conversation USING btree (tenant_id, owner_user_id, pinned DESC, updated_at DESC);

-- Retention purge: unpinned conversations idle beyond the cutoff, per tenant.
CREATE INDEX idx_mcp_conversation_purge
    ON public.mcp_conversation USING btree (tenant_id, pinned, updated_at);

-- A conversation's messages in order (reopen, memory hydration, cascade delete). Leads with
-- tenant_id, so no separate tenant index is needed on mcp_message.
CREATE INDEX idx_mcp_message_conversation_created
    ON public.mcp_message USING btree (tenant_id, conversation_id, created_at);

ALTER TABLE public.mcp_conversation ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.mcp_conversation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.mcp_conversation
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

ALTER TABLE public.mcp_message ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.mcp_message FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.mcp_message
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

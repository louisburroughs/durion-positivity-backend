-- Answer resolution ladder, rung 3: screen registry for deep-link fallback.
-- When no tool can answer a request, the assistant points at the correct UI screen
-- (with filters) instead of returning nothing. Screens are matched semantically —
-- `description` is embedded (nomic-embed-text, 768 dims) and searched by cosine
-- distance, mirroring the mcp_tool embedding approach.

CREATE TABLE IF NOT EXISTS mcp_screen_registry (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    screen_key    VARCHAR(120) NOT NULL UNIQUE,
    title         VARCHAR(200) NOT NULL,
    description   TEXT NOT NULL,
    domain        VARCHAR(80) NOT NULL,
    url_template  TEXT NOT NULL,
    required_perm VARCHAR(120),
    embedding     vector(768),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_mcp_screen_registry_domain ON mcp_screen_registry (domain);

-- Seed screens. Embeddings are left NULL and backfilled at startup by
-- ScreenEmbeddingInitializer. url_template placeholders ({status}, {locationId}, …)
-- are filled from resolved request params; unresolved query params are dropped.
INSERT INTO mcp_screen_registry (screen_key, title, description, domain, url_template, required_perm)
VALUES
    ('workorders.list', 'Work Orders',
     'Browse and filter work orders by status (open, in progress, awaiting parts, completed, cancelled) and by location. Use to see how many work orders are open or to find a specific work order.',
     'workorder', '/workorders?status={status}&location={locationId}', 'workorder:workorder:view'),
    ('workorders.wip', 'Work In Progress',
     'Live board of active work-in-progress work orders for a location, including assigned technician and current status.',
     'workorder', '/workorders/wip?location={locationId}', 'workorder:wip:view'),
    ('invoices.list', 'Invoices',
     'Browse invoices and filter by payment status such as open, unpaid, paid, or overdue balances.',
     'invoice', '/invoices?status={status}', 'invoice:invoice:view')
ON CONFLICT (screen_key) DO NOTHING;

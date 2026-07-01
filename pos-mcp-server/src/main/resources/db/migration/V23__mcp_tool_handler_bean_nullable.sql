-- Gate 3 (G3.1 persistence): OpenAPI-discovered operations (source='openapi') are executed from
-- their persisted coordinates via OpenApiOperationExecutor, not from a Spring bean, so they have no
-- handler_bean. Relax the NOT NULL constraint; facade rows keep supplying it.
ALTER TABLE mcp_tool ALTER COLUMN handler_bean DROP NOT NULL;

-- Gate 3 (G3.1 persistence): OpenAPI-discovered operations have no handler_bean (H2 variant of pg
-- V23). Relax the NOT NULL constraint; facade rows keep supplying it.
ALTER TABLE mcp_tool ALTER COLUMN handler_bean SET NULL;

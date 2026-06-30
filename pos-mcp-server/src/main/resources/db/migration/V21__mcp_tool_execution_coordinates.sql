-- Gate 3 (G3.1): persist execution coordinates for OpenAPI-discovered operations so they can be
-- built into agent-callable tools at request time. Facade rows leave these null.
ALTER TABLE mcp_tool ADD COLUMN IF NOT EXISTS http_method  VARCHAR(8);
ALTER TABLE mcp_tool ADD COLUMN IF NOT EXISTS http_path    VARCHAR(512);
ALTER TABLE mcp_tool ADD COLUMN IF NOT EXISTS service_id   VARCHAR(128);
ALTER TABLE mcp_tool ADD COLUMN IF NOT EXISTS input_schema TEXT;

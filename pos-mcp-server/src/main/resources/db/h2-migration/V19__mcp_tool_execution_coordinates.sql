-- Gate 3 (G3.1): execution coordinates for OpenAPI-discovered operations (H2 variant of pg V21).
ALTER TABLE mcp_tool ADD COLUMN IF NOT EXISTS http_method  VARCHAR(8);
ALTER TABLE mcp_tool ADD COLUMN IF NOT EXISTS http_path    VARCHAR(512);
ALTER TABLE mcp_tool ADD COLUMN IF NOT EXISTS service_id   VARCHAR(128);
ALTER TABLE mcp_tool ADD COLUMN IF NOT EXISTS input_schema TEXT;

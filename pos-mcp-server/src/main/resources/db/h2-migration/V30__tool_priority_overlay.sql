-- ADR-0062 plan WS6: per-tenant tool-priority overlay, the H2 variant of the Postgres baseline's
-- mcp_tool_priority. Postgres fills tenant_id from app_current_tenant() and confines the rows with
-- row-level security; H2 has neither, so the column defaults to the alpha default tenant like V29.
-- No foreign key: the H2 chain never carried mcp_tool (it exists only in the Postgres chain).
CREATE TABLE IF NOT EXISTS mcp_tool_priority (
  tenant_id UUID DEFAULT '01900000-0000-7000-8000-000000000001' NOT NULL,
  tool_id UUID NOT NULL,
  priority DOUBLE PRECISION NOT NULL,
  avg_latency_ms INT NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL,
  PRIMARY KEY (tenant_id, tool_id)
);

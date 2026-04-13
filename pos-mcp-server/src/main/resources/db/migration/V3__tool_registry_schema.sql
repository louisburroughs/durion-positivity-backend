-- mcp_role: known roles
CREATE TABLE IF NOT EXISTS mcp_role (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name VARCHAR(80) NOT NULL,
  CONSTRAINT uk_mcp_role_name UNIQUE (name)
);
-- mcp_workflow_state: known workflow states
CREATE TABLE IF NOT EXISTS mcp_workflow_state (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name VARCHAR(80) NOT NULL,
  CONSTRAINT uk_mcp_workflow_state_name UNIQUE (name)
);
-- mcp_tool: facade tool catalog
CREATE TABLE IF NOT EXISTS mcp_tool (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name VARCHAR(100) NOT NULL,
  display_name VARCHAR(150) NOT NULL,
  description TEXT NOT NULL,
  domain VARCHAR(80) NOT NULL,
  priority DOUBLE PRECISION NOT NULL DEFAULT 1.0,
  cost_level VARCHAR(20) NOT NULL DEFAULT 'low',
  avg_latency_ms INTEGER NOT NULL DEFAULT 200,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  handler_bean VARCHAR(150) NOT NULL,
  embedding vector(768),
  CONSTRAINT uk_mcp_tool_name UNIQUE (name)
);
-- mcp_tool_role: many-to-many tool ↔ role
CREATE TABLE IF NOT EXISTS mcp_tool_role (
  tool_id UUID NOT NULL REFERENCES mcp_tool(id),
  role_id UUID NOT NULL REFERENCES mcp_role(id),
  PRIMARY KEY (tool_id, role_id)
);
-- mcp_tool_workflow: many-to-many tool ↔ workflow state
CREATE TABLE IF NOT EXISTS mcp_tool_workflow (
  tool_id UUID NOT NULL REFERENCES mcp_tool(id),
  workflow_state_id UUID NOT NULL REFERENCES mcp_workflow_state(id),
  PRIMARY KEY (tool_id, workflow_state_id)
);
CREATE INDEX IF NOT EXISTS idx_mcp_tool_role_tool_id ON mcp_tool_role (tool_id);
CREATE INDEX IF NOT EXISTS idx_mcp_tool_role_role_id ON mcp_tool_role (role_id);
CREATE INDEX IF NOT EXISTS idx_mcp_tool_workflow_tool_id ON mcp_tool_workflow (tool_id);
CREATE TABLE mcp_tool_permission (
    tool_id UUID NOT NULL REFERENCES mcp_tool(id) ON DELETE CASCADE,
    permission_code VARCHAR(150) NOT NULL,
    PRIMARY KEY (tool_id, permission_code)
);

CREATE INDEX idx_mcp_tool_permission_code ON mcp_tool_permission (permission_code);

ALTER TABLE mcp_tool ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'facade';
ALTER TABLE mcp_tool ADD COLUMN http_method VARCHAR(10);
ALTER TABLE mcp_tool ADD COLUMN path_template VARCHAR(500);
ALTER TABLE mcp_tool ADD COLUMN operation_id VARCHAR(150);

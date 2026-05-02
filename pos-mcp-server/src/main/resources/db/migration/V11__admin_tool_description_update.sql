UPDATE mcp_tool
SET description = 'User management, platform administration, and access governance. '
    || 'Use to list all platform users, check user counts, retrieve user roles and permissions, '
    || 'search audit logs, and check platform health status.',
    embedding    = NULL
WHERE name = 'AdminFacadeTool';

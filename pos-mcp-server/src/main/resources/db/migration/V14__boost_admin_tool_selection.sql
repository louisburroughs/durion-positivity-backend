UPDATE mcp_tool
SET description = 'User management, platform administration, and access governance. '
    || 'Use first for questions about platform users, user counts, roles, permissions, access control, '
    || 'audit lookups, and administrative access reviews.',
    priority = 1.3,
    cost_level = 'low',
    embedding = NULL
WHERE name = 'AdminFacadeTool';

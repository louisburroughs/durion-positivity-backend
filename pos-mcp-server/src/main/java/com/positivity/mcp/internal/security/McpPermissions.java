package com.positivity.mcp.internal.security;

/**
 * Permission constants enforced by pos-mcp-server endpoints.
 */
public final class McpPermissions {

    public static final String LLM_API_VIEW = "mcp:llm_api:view";
    public static final String LLM_API_CREATE = "mcp:llm_api:create";
    public static final String LLM_API_UPDATE = "mcp:llm_api:update";
    public static final String LLM_API_DELETE = "mcp:llm_api:delete";

    public static final String SYSTEM_PROMPT_VIEW = "mcp:system_prompt:view";
    public static final String SYSTEM_PROMPT_CREATE = "mcp:system_prompt:create";
    public static final String SYSTEM_PROMPT_UPDATE = "mcp:system_prompt:update";
    public static final String SYSTEM_PROMPT_DELETE = "mcp:system_prompt:delete";

    public static final String TOOL_VIEW = "mcp:tool:view";
    public static final String TOOL_MANAGE = "mcp:tool:manage";

    public static final String NLTI_REQUEST_SUBMIT = "nlti:request:submit";
    public static final String NLTI_REQUEST_READ = "nlti:request:read";
    public static final String NLTI_AUDIT_READ = "nlti:audit:read";
    public static final String MCP_DOCUMENT_INGEST = "mcp:document:ingest";
    public static final String MCP_CHAT_STREAM = "mcp:chat:stream";
    public static final String MCP_CHAT_EXECUTE = "mcp:chat:execute";

    private McpPermissions() {}
}

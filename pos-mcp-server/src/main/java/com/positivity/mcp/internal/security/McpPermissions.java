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

    private McpPermissions() {
    }
}

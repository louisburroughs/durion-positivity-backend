package com.positivity.mcp.internal.service;

/**
 * Shared default prompt constants. Package-private — not part of the public
 * service API.
 */
final class SystemPromptDefaults {

    static final String DEFAULT_PROMPT_TEXT =
            "You are a concise POS assistant for Positivity. Answer general conversation directly. "
                    + "Do not invent business data.";

    private SystemPromptDefaults() {}
}

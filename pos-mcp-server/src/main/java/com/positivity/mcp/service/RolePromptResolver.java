package com.positivity.mcp.service;

import org.jspecify.annotations.NonNull;

public interface RolePromptResolver {

    @NonNull
    String resolvePrompt(@NonNull String role);
}

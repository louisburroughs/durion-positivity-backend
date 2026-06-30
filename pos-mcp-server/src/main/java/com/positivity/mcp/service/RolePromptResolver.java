package com.positivity.mcp.service;

import java.util.List;
import org.jspecify.annotations.NonNull;

public interface RolePromptResolver {

    @NonNull
    String resolvePrompt(@NonNull String promptName);

    /**
     * Assembles the layered system prompt for a request, role-first: BASE + ROLE persona + DOMAIN
     * (by RAG scope) + TOOL-USE contract. Absent layers (no seeded persona for the role, or a
     * master/shared scope with no domain prompt) are skipped. The returned {@code layers} lists
     * exactly the layers composed, for telemetry.
     *
     * <p>Role drives persona only; it never affects tool or document access.
     */
    @NonNull
    AssembledPrompt assemble(@NonNull String role, @NonNull String ragScope);

    /** Result of {@link #assemble(String, String)}: composed prompt text and the layers included. */
    record AssembledPrompt(@NonNull String text, @NonNull List<String> layers) {}
}

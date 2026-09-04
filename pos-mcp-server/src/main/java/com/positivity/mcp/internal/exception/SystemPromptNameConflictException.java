package com.positivity.mcp.internal.exception;

/**
 * A system prompt create/update was rejected because another prompt already uses the requested
 * name. A stateful collision against existing data (the payload itself is well-formed), so this
 * maps to {@code 409 Conflict} per ADR-0017, not {@code 400}.
 */
public class SystemPromptNameConflictException extends RuntimeException {
    public SystemPromptNameConflictException(String message) {
        super(message);
    }
}

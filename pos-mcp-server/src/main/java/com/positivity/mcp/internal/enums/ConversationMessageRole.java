package com.positivity.mcp.internal.enums;

import java.util.Locale;
import org.jspecify.annotations.NonNull;

/**
 * Who produced a persisted conversation turn (#2073). Stored and exposed as the lower-case wire value
 * ({@code user} / {@code assistant}), which is what the {@code mcp_message.role} CHECK constraint and
 * the {@code ConversationMessage.role} contract both carry.
 */
public enum ConversationMessageRole {
    USER("user"),
    ASSISTANT("assistant");

    private final String wireValue;

    ConversationMessageRole(String wireValue) {
        this.wireValue = wireValue;
    }

    public @NonNull String wireValue() {
        return wireValue;
    }

    /**
     * Resolves a wire value.
     *
     * @throws IllegalArgumentException when {@code value} is neither {@code user} nor {@code assistant}
     */
    public static @NonNull ConversationMessageRole fromWireValue(@NonNull String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ConversationMessageRole role : values()) {
            if (role.wireValue.equals(normalized)) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unknown conversation message role: " + value);
    }
}

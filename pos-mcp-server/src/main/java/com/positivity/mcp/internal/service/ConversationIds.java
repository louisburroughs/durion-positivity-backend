package com.positivity.mcp.internal.service;

import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Conversation id syntax (#2073). A persisted conversation id is a UUID in canonical 8-4-4-4-12 hex
 * form; anything else a chat caller sends is the deprecated ephemeral isolation key (#1735). The
 * check is strict on purpose: {@link UUID#fromString} also accepts non-canonical forms such as
 * {@code 1-2-3-4-5}, which an ephemeral key could collide with.
 */
public final class ConversationIds {

    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private ConversationIds() {}

    /** {@code value} as a UUID when it is in canonical form, else {@code null}. */
    public static @Nullable UUID parseCanonical(@Nullable String value) {
        if (value == null || !CANONICAL_UUID.matcher(value).matches()) {
            return null;
        }
        return UUID.fromString(value);
    }
}

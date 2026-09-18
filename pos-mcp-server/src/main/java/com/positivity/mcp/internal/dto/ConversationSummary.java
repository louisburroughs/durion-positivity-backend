package com.positivity.mcp.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Row of the caller's conversation history rail (#2073).
 *
 * <p>Ownership is resolved server-side from the token ({@code tid}/{@code sub}, ADR-0062) — never
 * from a body/query/header/route value — so this list only ever contains the caller's own
 * conversations. Pinned conversations sort first, then {@code updatedAt} descending; the list is
 * capped at 200 rows (no pagination).
 */
@Schema(
        name = "ConversationSummary",
        description = "One entry in the caller's conversation history rail.",
        requiredProperties = {"id", "title", "createdAt", "updatedAt", "pinned"})
public record ConversationSummary(
        @Schema(description = "Conversation id.", example = "0198f2b1-6c2a-7c3e-8f00-1234567890ab") @NonNull
        UUID id,

        @Schema(
                description = "Conversation title. Derived from the first user message until "
                        + "the caller renames it via PATCH.",
                example = "Mechanic roster count")
        @NonNull
        String title,

        @Schema(
                description = "First line of the latest assistant turn, truncated to 90 characters. "
                        + "Null for a conversation with no assistant turn yet.",
                example = "You have 26 mechanics, all ACTIVE.",
                nullable = true)
        @Nullable
        String preview,

        @Schema(description = "When the conversation was created.") @NonNull
        Instant createdAt,

        @Schema(description = "When the conversation was last touched (new turn, rename, pin toggle).") @NonNull
        Instant updatedAt,

        @Schema(
                description = "Whether the caller pinned this conversation. Pinned conversations sort first "
                        + "and are exempt from retention purge.")
        boolean pinned) {}

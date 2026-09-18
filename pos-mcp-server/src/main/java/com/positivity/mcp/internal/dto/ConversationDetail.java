package com.positivity.mcp.internal.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A conversation with its full message history (#2073).
 *
 * <p>Mirrors every {@link ConversationSummary} field plus {@code messages} (records cannot
 * extend one another, so the fields are repeated rather than inherited). Returned by {@code
 * GET /v1/mcp/conversations/{id}}; a bare {@code List<ConversationSummary>} — with no {@code
 * messages} — is what {@code GET /v1/mcp/conversations} returns instead. Retention is not
 * repeated here: it is the same value for every conversation, so it is exposed once by
 * {@code GET /v1/mcp/conversations/policy} ({@link ConversationPolicy}) rather than per row.
 */
@Schema(
        name = "ConversationDetail",
        description = "A conversation and its full message history.",
        requiredProperties = {"id", "title", "createdAt", "updatedAt", "pinned", "messages"})
public record ConversationDetail(
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
        boolean pinned,

        @ArraySchema(
                schema = @Schema(implementation = ConversationMessage.class),
                arraySchema =
                        @Schema(
                                description = "Every message in the conversation, oldest first. Reopening a "
                                        + "conversation must render identically to when it was live."))
        @NonNull
        List<ConversationMessage> messages) {}

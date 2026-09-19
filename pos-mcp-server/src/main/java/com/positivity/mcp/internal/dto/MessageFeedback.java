package com.positivity.mcp.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The caller's stored rating of one assistant {@link ConversationMessage} (#2075). Embedded on
 * {@code ConversationMessage.feedback}; null when the message is unrated.
 */
@Schema(
        name = "MessageFeedback",
        description = "The caller's current rating of this assistant message.",
        requiredProperties = {"rating", "ratedAt"})
public record MessageFeedback(
        @Schema(allowableValues = {"helpful", "not_helpful"}) @NonNull
        String rating,

        @Schema(
                allowableValues = {"incorrect", "incomplete", "not_relevant", "other"},
                nullable = true)
        @Nullable
        String reason,

        @Schema(nullable = true) @Nullable String comment,
        @NonNull Instant ratedAt) {}

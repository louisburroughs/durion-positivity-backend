package com.positivity.mcp.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response of {@code GET /v1/mcp/conversations/policy} (#2073, user decision U2).
 *
 * <p>Retention is a single server-wide value ({@code mcp.conversation.retention-days}, default 30),
 * not a per-conversation one, so it is exposed once here rather than repeated on every {@link
 * ConversationSummary}/{@link ConversationDetail} row — a caller with zero conversations still
 * needs this value for the history rail's footer note, and a per-row field would be unavailable
 * exactly then.
 */
@Schema(
        name = "ConversationPolicy",
        description = "Server-wide conversation retention policy.",
        requiredProperties = {"retentionDays", "pinnedExempt"})
public record ConversationPolicy(
        @Schema(
                description = "Days an unpinned conversation may sit idle (no new turns, no rename/pin change) "
                        + "before the retention purge deletes it.",
                example = "30")
        int retentionDays,

        @Schema(
                description = "Whether pinned conversations are exempt from the retention purge regardless "
                        + "of idle time.",
                example = "true")
        boolean pinnedExempt) {}

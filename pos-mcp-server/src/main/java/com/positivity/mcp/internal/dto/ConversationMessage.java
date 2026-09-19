package com.positivity.mcp.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One turn of a persisted conversation (#2073).
 *
 * <p>{@code content} is the raw text — the caller's own words for a {@code user} message, or the
 * assistant's full markdown answer for an {@code assistant} message. {@code blocks} is the same
 * {@link ChatBlock} union {@code POST /mcp/chat} returns, segmented from that same text.
 *
 * <p><strong>{@code blocks} may be empty (plan decision O1).</strong> Segmentation is a faithful
 * source-order split of {@code content} or nothing at all — never a partial one — so a blank
 * input, a parser failure, or a table/fenced code block nested inside a list item or block quote
 * all yield an empty {@code blocks} array. A client must render {@code content} directly whenever
 * {@code blocks} is empty; {@code content} is therefore always populated and is never itself
 * empty for a stored message.
 */
@Schema(
        name = "ConversationMessage",
        description = "One turn (user or assistant) of a persisted conversation.",
        requiredProperties = {"id", "role", "timestamp", "blocks", "content"})
public record ConversationMessage(
        @Schema(description = "Message id.", example = "0198f2b1-7a10-7b21-9c00-abcdef123456") @NonNull
        UUID id,

        @Schema(
                description = "Who produced this turn.",
                example = "assistant",
                allowableValues = {"user", "assistant"})
        @NonNull
        String role,

        @Schema(description = "When the message was recorded.") @NonNull
        Instant timestamp,

        @ArraySchema(
                schema = @Schema(implementation = ChatBlock.class),
                arraySchema =
                        @Schema(
                                description = "Typed rendering units segmented from `content`, in source order. "
                                        + "May be empty (never partial) — when empty, render `content` instead."))
        @NonNull
        List<ChatBlock> blocks,

        @Schema(
                description = "Raw text/markdown for this turn. Always populated; the fallback a client "
                        + "renders whenever `blocks` is empty.",
                example = "You have **26 mechanics**, all ACTIVE.")
        @NonNull
        String content,

        @Schema(
                description = "The caller's current rating of this assistant message, absent (or null) when "
                        + "the message is unrated. Always absent for user messages.",
                nullable = true,
                requiredMode = NOT_REQUIRED)
        @Nullable
        MessageFeedback feedback) {}

package com.positivity.mcp.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /v1/mcp/conversations/{id}/messages} (#2073) — a client-authored turn
 * appended directly to a conversation (localStorage import, or a non-chat caller), as distinct
 * from the turns {@code POST /mcp/chat} persists itself. <strong>Do not re-append a turn that
 * {@code /mcp/chat} already persisted</strong> — see the operation's documentation.
 *
 * <p>{@code blocks} count is bounded here (≤64); the ≤256&nbsp;KB serialized-size limit is
 * enforced by the service (a {@code jakarta.validation} annotation cannot see the wire size of a
 * polymorphic list cheaply, so the count bound is the annotation-level guard and the byte bound is
 * the service-level one). An unrecognized {@code blocks[].kind} fails Jackson polymorphic
 * deserialization before validation runs, and is answered as 400 by the module's exception
 * handler. Stored rows from this endpoint carry {@code origin = CLIENT} (chat-path rows carry
 * {@code origin = CHAT}) so grading can exclude client-authored turns.
 */
@Schema(name = "AppendMessageRequest", description = "Client-authored turn to append to a conversation.")
public record AppendMessageRequest(
        @Schema(
                description = "Who produced this turn.",
                example = "user",
                allowableValues = {"user", "assistant"},
                requiredMode = REQUIRED)
        @NotBlank
        @Pattern(regexp = "user|assistant", message = "role must be 'user' or 'assistant'")
        @NonNull
        String role,

        @ArraySchema(
                schema = @Schema(implementation = ChatBlock.class),
                minItems = 0,
                maxItems = 64,
                arraySchema =
                        @Schema(
                                description = "Typed rendering units for this turn. May be empty when `content` "
                                        + "carries plain text only. At most 64 entries.",
                                requiredMode = REQUIRED))
        @NotNull
        @Size(max = 64)
        List<@Valid ChatBlock> blocks,

        @Schema(
                description = "Optional raw text/markdown fallback for this turn, for clients that render "
                        + "`content` instead of `blocks`.",
                example = "How many mechanics do I have?",
                requiredMode = NOT_REQUIRED)
        @Nullable
        String content) {}

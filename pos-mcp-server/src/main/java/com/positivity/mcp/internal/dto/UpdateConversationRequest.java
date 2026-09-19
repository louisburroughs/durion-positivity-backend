package com.positivity.mcp.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code PATCH /v1/mcp/conversations/{id}} (#2073). Both fields are optional and
 * independent — a field omitted from the request leaves the corresponding stored value
 * unchanged. A request with neither field set is a no-op 200, not a 400. {@code title}, when
 * present, is trimmed and must be 1..120 characters after trimming (the {@link Size} annotation
 * bounds the raw value; the service is authoritative on the trimmed length).
 */
@Schema(
        name = "UpdateConversationRequest",
        description = "Partial update for a conversation's title and/or pinned state.")
public record UpdateConversationRequest(
        @Schema(
                description = "New title, 1..120 characters after trimming. Omit to leave the title unchanged.",
                example = "Mechanic roster (renamed)",
                requiredMode = NOT_REQUIRED)
        @Size(min = 1, max = 120)
        @Nullable
        String title,

        @Schema(
                description = "New pinned state. Omit to leave the pinned state unchanged. Pinned "
                        + "conversations are exempt from retention purge.",
                requiredMode = NOT_REQUIRED)
        @Nullable
        Boolean pinned) {}

package com.positivity.mcp.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /v1/mcp/conversations} (#2073). {@code title} is optional; when omitted the
 * conversation starts untitled and the server derives a title from the first message appended to
 * it. When present the service trims the value and enforces 1..120 characters after trimming (the
 * {@link Size} annotation bounds the raw value; the service is authoritative on the trimmed
 * length).
 */
@Schema(name = "CreateConversationRequest", description = "Request to start a new conversation.")
public record CreateConversationRequest(
        @Schema(
                description = "Optional initial title, 1..120 characters after trimming. Omit to let the "
                        + "server derive a title from the first appended message.",
                example = "Mechanic roster count",
                requiredMode = NOT_REQUIRED)
        @Size(min = 1, max = 120)
        @Nullable
        String title) {}

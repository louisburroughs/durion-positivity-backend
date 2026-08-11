package com.positivity.mcp.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Gate 6 (#1193): a write-action plan surfaced to the caller — as a preview awaiting confirmation,
 * or as the outcome of a confirm/cancel call. The {@code args} echoed here are the exact persisted
 * arguments that will be (or were) executed; confirmation never re-parses the user's text.
 */
@Schema(name = "WritePlanResponseV1", description = "Write-action plan preview / confirmation outcome (version 1)")
public record WritePlanResponseV1(
        @Schema(description = "Write plan identifier", requiredMode = REQUIRED)
        UUID planId,

        @Schema(description = "Originating NLTI request identifier", requiredMode = REQUIRED)
        UUID requestId,

        @Schema(description = "Conversation session identifier", requiredMode = REQUIRED)
        UUID sessionId,

        @Schema(description = "Plan lifecycle status", example = "PENDING_CONFIRMATION", requiredMode = REQUIRED)
        String status,

        @Schema(description = "Tool the plan will invoke on confirmation", requiredMode = REQUIRED)
        String targetTool,

        @Schema(description = "Exact arguments that will be executed on confirmation", requiredMode = REQUIRED)
        Map<String, Object> args,

        @Schema(
                description = "Argument names filled by an inferred default (disclosed per Gate 6)",
                requiredMode = REQUIRED)
        Set<String> inferredDefaultArgs,

        @Schema(description = "Assessed risk level", example = "MEDIUM", requiredMode = REQUIRED)
        String riskLevel,

        @Schema(description = "Human-readable summary of the planned action", requiredMode = REQUIRED)
        String summary,

        @Schema(
                description = "Idempotency key; re-sending confirm with this key never double-executes",
                requiredMode = REQUIRED)
        String idempotencyKey,

        @Schema(description = "Instant after which the plan can no longer be confirmed", requiredMode = REQUIRED)
        OffsetDateTime expiresAt,

        @Schema(description = "Instant the plan was executed, when complete", requiredMode = NOT_REQUIRED)
        OffsetDateTime executedAt,

        @Schema(description = "Execution outcome returned by the tool, when complete", requiredMode = NOT_REQUIRED)
        String executionResult) {}

package com.positivity.workorder.internal.dto;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for starting a work session")
public class StartWorkSessionRequest {

    @NotNull
    @Schema(description = "ID of the mechanic starting the session")
    private UUID mechanicId;

    @NotNull
    @Schema(description = "ID of the work order")
    private UUID workOrderId;

    @NotNull
    @Schema(description = "ID of the work order task")
    private UUID workOrderTaskId;

    @NotNull
    @Schema(description = "ID of the work location")
    private UUID locationId;

    @Nullable
    @Schema(description = "Optional resource/bay ID")
    private UUID resourceId;

    @Nullable
    @Schema(description = "Reason for overriding overlap policy (requires permission)")
    private String overlapOverrideReason;

    @Nullable
    @Schema(description = "Client-provided idempotency key to prevent duplicate session starts")
    private String idempotencyKey;
}

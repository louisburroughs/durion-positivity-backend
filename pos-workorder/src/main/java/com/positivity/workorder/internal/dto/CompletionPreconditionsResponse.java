package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Completion precondition evaluation response")
public class CompletionPreconditionsResponse {

    @Schema(
            description = "Workorder identifier",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID workorderId;

    @Schema(description = "Whether the workorder can be completed", example = "true", requiredMode = REQUIRED)
    @NotNull
    private Boolean canComplete;

    @Schema(description = "Current status", example = "WORK_IN_PROGRESS", requiredMode = REQUIRED)
    @NotNull
    private String currentStatus;

    @Schema(
            description = "Checklist items evaluated before completion",
            example = "[\"All service items completed\"]",
            requiredMode = NOT_REQUIRED)
    private List<String> checklistItems;

    @Schema(
            description = "Blocking reasons that prevent completion",
            example = "[\"Unresolved approval-gated change request\"]",
            requiredMode = NOT_REQUIRED)
    private List<String> blockingReasons;

    @Schema(
            description = "Number of unresolved approval-gated change requests",
            example = "2",
            requiredMode = NOT_REQUIRED)
    private Integer unresolvedApprovalGatedChangeRequests;

    @Schema(
            description = "Number of service items not in COMPLETED/CANCELLED status",
            example = "2",
            requiredMode = NOT_REQUIRED)
    private Integer nonTerminalServiceItems;

    @Schema(
            description = "Number of part items not in COMPLETED/CANCELLED status",
            example = "2",
            requiredMode = NOT_REQUIRED)
    private Integer nonTerminalPartItems;

    @Schema(
            description = "Whether emergency denial acknowledgment requirements are satisfied",
            example = "true",
            requiredMode = NOT_REQUIRED)
    private Boolean emergencyDenialAcknowledged;

    @Schema(
            description = "Whether at least one billable item exists for final snapshot",
            example = "true",
            requiredMode = NOT_REQUIRED)
    private Boolean hasBillableItems;
}

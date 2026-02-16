package com.positivity.workorder.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Completion precondition evaluation response")
public class CompletionPreconditionsResponse {

    @Schema(description = "Workorder identifier")
    private UUID workorderId;

    @Schema(description = "Whether the workorder can be completed")
    private Boolean canComplete;

    @Schema(description = "Current status")
    private String currentStatus;

    @Schema(description = "Checklist items evaluated before completion")
    private List<String> checklistItems;

    @Schema(description = "Blocking reasons that prevent completion")
    private List<String> blockingReasons;

    @Schema(description = "Number of unresolved approval-gated change requests")
    private Integer unresolvedApprovalGatedChangeRequests;

    @Schema(description = "Number of service items not in COMPLETED/CANCELLED status")
    private Integer nonTerminalServiceItems;

    @Schema(description = "Number of part items not in COMPLETED/CANCELLED status")
    private Integer nonTerminalPartItems;

    @Schema(description = "Whether emergency denial acknowledgment requirements are satisfied")
    private Boolean emergencyDenialAcknowledged;

    @Schema(description = "Whether at least one billable item exists for final snapshot")
    private Boolean hasBillableItems;
}

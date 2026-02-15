package com.positivity.workorder.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Capability flags indicating what actions the current user can perform on a
 * workorder.
 * Used for frontend UI gating without redundant permission checks.
 * 
 * <p>
 * Implements CAP-005 Story #155 - Role-Based Visibility
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Capability flags for workorder actions based on user authorities")
public class WorkorderCapabilities {

    @Schema(description = "User can start workorder", example = "true")
    @Builder.Default
    private Boolean canStart = false;

    @Schema(description = "User can approve workorder", example = "false")
    @Builder.Default
    private Boolean canApprove = false;

    @Schema(description = "User can assign/reassign technicians", example = "true")
    @Builder.Default
    private Boolean canAssignTechnician = false;

    @Schema(description = "User can record labor entries", example = "true")
    @Builder.Default
    private Boolean canRecordLabor = false;

    @Schema(description = "User can record parts usage", example = "true")
    @Builder.Default
    private Boolean canRecordPartsUsage = false;

    @Schema(description = "User can view financial data", example = "false")
    @Builder.Default
    private Boolean canViewFinancials = false;

    @Schema(description = "User can edit workorder", example = "true")
    @Builder.Default
    private Boolean canEditWorkorder = false;

    @Schema(description = "User can delete workorder", example = "false")
    @Builder.Default
    private Boolean canDeleteWorkorder = false;
}

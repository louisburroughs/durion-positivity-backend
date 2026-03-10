package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.entity.ChangeRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO for ChangeRequest responses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response DTO for change requests")
public class ChangeRequestResponse {

    @Schema(description = "Unique identifier for the change request", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID id;

    @Schema(description = "Workorder ID", example = "550e8400-e29b-41d4-a716-446655440001")
    private UUID workorderId;

    @Schema(description = "User ID who requested the change", example = "system@positivity.com")
    private String requestedByUserId;

    @Schema(description = "Date and time the change was requested")
    private LocalDateTime requestedAt;

    @Schema(description = "Change request status", example = "AWAITING_ADVISOR_REVIEW")
    private String status;

    @Schema(description = "Description of the change request")
    private String description;

    @Schema(description = "Whether this is an emergency exception")
    private Boolean isEmergencyException;

    @Schema(description = "Reason for the exception if applicable")
    private String exceptionReason;

    @Schema(description = "Approval notes")
    private String approvalNote;

    @Schema(description = "Whether approval is gated")
    private Boolean isApprovalGated;

    @Schema(description = "Date and time the change was approved")
    private LocalDateTime approvedAt;

    @Schema(description = "User ID who approved the change", example = "system@positivity.com")
    private String approvedBy;

    @Schema(description = "Date and time the change was declined")
    private LocalDateTime declinedAt;

    /**
     * Convert entity to response DTO
     */
    public static ChangeRequestResponse fromEntity(ChangeRequest entity) {
        if (entity == null) {
            return null;
        }

        return ChangeRequestResponse.builder()
                .id(entity.getId())
            .workorderId(entity.getWorkorder() != null ? entity.getWorkorder().getId() : null)
                .requestedByUserId(entity.getRequestedByUserId())
                .requestedAt(entity.getRequestedAt())
                .status(entity.getStatus() != null ? entity.getStatus().name() : null)
                .description(entity.getDescription())
                .isEmergencyException(entity.getIsEmergencyException())
                .exceptionReason(entity.getExceptionReason())
                .approvalNote(entity.getApprovalNote())
                .isApprovalGated(entity.getIsApprovalGated())
                .approvedAt(entity.getApprovedAt())
                .approvedBy(entity.getApprovedBy())
                .declinedAt(entity.getDeclinedAt())
                .build();
    }
}

package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.entity.TechnicianAssignment;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Mapper for converting TechnicianAssignment entities to DTOs.
 */
public final class TechnicianAssignmentMapper {

    private TechnicianAssignmentMapper() {
        // Utility class
    }

    /**
     * Convert entity and status to response DTO for assignment operation.
     */
    public static TechnicianAssignmentResponse toAssignmentResponse(
            @NonNull TechnicianAssignment assignment,
            @NonNull WorkorderStatus workorderStatus,
            String previousTechnicianId,
            @NonNull String message) {
        return TechnicianAssignmentResponse.fromAssignment(
                assignment, workorderStatus.name(), previousTechnicianId, message);
    }

    /**
     * Convert entity to response with full history.
     */
    public static TechnicianAssignmentResponse toResponseWithHistory(
            @NonNull TechnicianAssignment currentAssignment,
            @NonNull List<TechnicianAssignment> history,
            @NonNull WorkorderStatus workorderStatus) {
        return TechnicianAssignmentResponse.withHistory(currentAssignment, history, workorderStatus.name());
    }

    /**
     * Build reassignment response from entities and status.
     */
    public static TechnicianAssignmentResponse toReassignmentResponse(
            @NonNull TechnicianAssignment newAssignment,
            UUID previousTechnicianId,
            @NonNull WorkorderStatus workorderStatus,
            @NonNull String reason,
            @NonNull String reassignedBy) {
        return TechnicianAssignmentResponse.builder()
                .workorderId(newAssignment.getWorkorderId().toString())
                .technicianId(newAssignment.getTechnicianId().toString())
                .assignedAt(newAssignment.getAssignedAt())
                .assignedBy(newAssignment.getAssignedBy())
                .previousTechnicianId(previousTechnicianId != null ? previousTechnicianId.toString() : null)
                .status(workorderStatus.name())
                .reassignmentReason(reason)
                .reassignedAt(newAssignment.getAssignedAt())
                .reassignedBy(reassignedBy)
                .message("Technician reassigned successfully")
                .build();
    }
}

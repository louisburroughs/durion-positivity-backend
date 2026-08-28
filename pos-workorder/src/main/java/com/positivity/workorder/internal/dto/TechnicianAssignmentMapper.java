package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.enums.WorkorderStatus;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Mapper for converting {@link TechnicianAssignmentRecord} domain snapshots to response DTOs.
 */
public final class TechnicianAssignmentMapper {

    private TechnicianAssignmentMapper() {
        // Utility class
    }

    /**
     * Convert an assignment snapshot and status to response DTO for an assignment operation.
     */
    public static TechnicianAssignmentResponse toAssignmentResponse(
            @NonNull TechnicianAssignmentRecord assignment,
            @NonNull WorkorderStatus workorderStatus,
            String previousTechnicianId,
            @NonNull String message) {
        return TechnicianAssignmentResponse.fromAssignment(
                assignment, workorderStatus.name(), previousTechnicianId, message);
    }

    /**
     * Convert an assignment snapshot to a response with full history.
     */
    public static TechnicianAssignmentResponse toResponseWithHistory(
            @NonNull TechnicianAssignmentRecord currentAssignment,
            @NonNull List<TechnicianAssignmentRecord> history,
            @NonNull WorkorderStatus workorderStatus) {
        return TechnicianAssignmentResponse.withHistory(currentAssignment, history, workorderStatus.name());
    }

    /**
     * Build reassignment response from assignment snapshots and status.
     */
    public static TechnicianAssignmentResponse toReassignmentResponse(
            @NonNull TechnicianAssignmentRecord newAssignment,
            UUID previousTechnicianId,
            @NonNull WorkorderStatus workorderStatus,
            @NonNull String reason,
            @NonNull String reassignedBy) {
        return TechnicianAssignmentResponse.builder()
                .workorderId(newAssignment.workorderId().toString())
                .technicianId(newAssignment.technicianId().toString())
                .assignedAt(newAssignment.assignedAt())
                .assignedBy(newAssignment.assignedBy())
                .previousTechnicianId(previousTechnicianId != null ? previousTechnicianId.toString() : null)
                .status(workorderStatus.name())
                .reassignmentReason(reason)
                .reassignedAt(newAssignment.assignedAt())
                .reassignedBy(reassignedBy)
                .message("Technician reassigned successfully")
                .build();
    }
}

package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.entity.TechnicianAssignment;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Response DTO for technician assignment operations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Technician assignment response")
public class TechnicianAssignmentResponse {

    @Schema(description = "Work order ID", example = "550e8400-e29b-41d4-a716-446655440001")
    private String workorderId;

    @Schema(description = "Assigned technician ID", example = "550e8400-e29b-41d4-a716-446655440050")
    private String technicianId;

    @Schema(description = "Technician display name", example = "John Smith")
    private String technicianName;

    @Schema(description = "When the technician was assigned", example = "2024-01-27T14:30:00")
    private LocalDateTime assignedAt;

    @Schema(description = "ID of user who assigned the technician", example = "550e8400-e29b-41d4-a716-446655440000")
    private String assignedBy;

    @Schema(description = "Previous technician ID if this was a reassignment", example = "550e8400-e29b-41d4-a716-446655440049")
    private String previousTechnicianId;

    @Schema(description = "Previous technician name if this was a reassignment", example = "Jane Doe")
    private String previousTechnicianName;

    @Schema(description = "Status of the workorder after assignment", example = "ASSIGNED")
    private String status;

    @Schema(description = "Operation result message", example = "Technician assigned successfully")
    private String message;

    @Schema(description = "Assignment notes")
    private String notes;

    @Schema(description = "Reason for reassignment if applicable")
    private String reassignmentReason;

    @Schema(description = "When technician was reassigned", example = "2024-01-27T15:30:00")
    private LocalDateTime reassignedAt;

    @Schema(description = "ID of user who performed reassignment", example = "550e8400-e29b-41d4-a716-446655440000")
    private String reassignedBy;

    @Schema(description = "Technician certifications", example = "[\"ASE Master Technician\", \"Brake Specialist\"]")
    private List<String> technicianCertifications;

    @Schema(description = "Current workorder status", example = "WORK_IN_PROGRESS")
    private String currentStatus;

    @Schema(description = "Full assignment history for the workorder")
    private List<AssignmentHistoryEntry> assignmentHistory;

    /**
     * Simple factory method for basic assignment response.
     */
    @NonNull
    public static TechnicianAssignmentResponse fromAssignment(
            @NonNull TechnicianAssignment assignment,
            @NonNull String workorderStatus,
            @Nullable String previousTechId,
            @Nullable String message) {
        return TechnicianAssignmentResponse.builder()
                .workorderId(assignment.getWorkorderId().toString())
                .technicianId(assignment.getTechnicianId().toString())
                .technicianName(null) // To be populated by controller if needed
                .assignedAt(assignment.getAssignedAt())
                .assignedBy(assignment.getAssignedBy().toString())
                .previousTechnicianId(previousTechId)
                .status(workorderStatus)
                .message(message)
                .notes(assignment.getNotes())
                .build();
    }

    /**
     * Nested DTO for assignment history entries.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Assignment history entry")
    public static class AssignmentHistoryEntry {
        @Schema(description = "Technician ID for this assignment period", example = "550e8400-e29b-41d4-a716-446655440050")
        private String technicianId;

        @Schema(description = "Technician display name", example = "John Smith")
        private String technicianName;

        @Schema(description = "When the technician was assigned", example = "2024-01-27T14:30:00")
        private LocalDateTime assignedAt;

        @Schema(description = "ID of user who assigned the technician", example = "550e8400-e29b-41d4-a716-446655440000")
        private String assignedBy;

        @Schema(description = "When the technician was unassigned (null for current assignment)", example = "2024-01-27T16:00:00")
        private LocalDateTime unassignedAt;

        @Schema(description = "Reason for reassignment/unassignment", example = "Technician called out sick")
        private String reason;

        @Schema(description = "Assignment notes")
        private String notes;

        /**
         * Convert entity to history entry DTO.
         */
        @NonNull
        public static AssignmentHistoryEntry fromEntity(@NonNull TechnicianAssignment assignment) {
            return AssignmentHistoryEntry.builder()
                    .technicianId(assignment.getTechnicianId().toString())
                    .technicianName(null) // To be populated if needed
                    .assignedAt(assignment.getAssignedAt())
                    .assignedBy(assignment.getAssignedBy().toString())
                    .unassignedAt(assignment.getUnassignedAt())
                    .reason(assignment.getReassignmentReason())
                    .notes(assignment.getNotes())
                    .build();
        }
    }

    /**
     * Create response with full history.
     */
    @NonNull
    public static TechnicianAssignmentResponse withHistory(
            @NonNull TechnicianAssignment currentAssignment,
            @NonNull List<TechnicianAssignment> history,
            @NonNull String currentWorkorderStatus) {
        List<AssignmentHistoryEntry> historyEntries = history.stream()
                .map(AssignmentHistoryEntry::fromEntity)
                .toList();

        return TechnicianAssignmentResponse.builder()
                .workorderId(currentAssignment.getWorkorderId().toString())
                .technicianId(currentAssignment.getTechnicianId().toString())
                .assignedAt(currentAssignment.getAssignedAt())
                .assignedBy(currentAssignment.getAssignedBy().toString())
                .currentStatus(currentWorkorderStatus)
                .assignmentHistory(historyEntries)
                .build();
    }
}

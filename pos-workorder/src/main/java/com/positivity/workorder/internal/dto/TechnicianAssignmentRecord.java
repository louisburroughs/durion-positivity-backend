package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.entity.TechnicianAssignment;
import java.time.LocalDateTime;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Domain snapshot of a technician assignment, decoupled from the JPA entity so
 * {@link com.positivity.workorder.internal.service.TechnicianAssignmentService} does not expose
 * persistence types across the service seam.
 */
public record TechnicianAssignmentRecord(
        @Nullable Long id,
        @NonNull UUID workorderId,
        @NonNull UUID technicianId,
        @NonNull LocalDateTime assignedAt,
        @NonNull String assignedBy,
        @Nullable LocalDateTime unassignedAt,
        @Nullable String reassignmentReason,
        @Nullable String notes,
        boolean current) {

    @NonNull
    public static TechnicianAssignmentRecord fromEntity(@NonNull TechnicianAssignment entity) {
        return new TechnicianAssignmentRecord(
                entity.getId(),
                entity.getWorkorderId(),
                entity.getTechnicianId(),
                entity.getAssignedAt(),
                entity.getAssignedBy(),
                entity.getUnassignedAt(),
                entity.getReassignmentReason(),
                entity.getNotes(),
                Boolean.TRUE.equals(entity.getCurrent()));
    }
}

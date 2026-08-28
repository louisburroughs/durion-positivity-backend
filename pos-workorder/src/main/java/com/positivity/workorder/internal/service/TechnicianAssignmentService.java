package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.dto.TechnicianAssignmentRecord;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface TechnicianAssignmentService {

    @NonNull
    TechnicianAssignmentRecord assignTechnician(
            @NonNull UUID workorderId, @NonNull UUID technicianId, @NonNull String assignedBy, @Nullable String notes);

    @NonNull
    TechnicianAssignmentRecord reassignTechnician(
            @NonNull UUID workorderId,
            @NonNull UUID newTechnicianId,
            @NonNull String reassignedBy,
            @Nullable String reason,
            @Nullable String notes);

    @NonNull
    /**
     * Releases the current technician assignment without creating a new one.
     *
     * <p>Distinct from {@link #reassignTechnician}, which always names a replacement: this is for
     * freeing capacity a job cannot currently use — a workorder blocked on an unresolved fleet
     * payment authorization, for instance — where there is nothing to reassign to yet.
     *
     * @return the released assignment, or empty when there was no current assignment
     */
    Optional<TechnicianAssignmentRecord> releaseAssignment(
            @NonNull UUID workorderId, @NonNull String releasedBy, @Nullable String reason);

    Optional<TechnicianAssignmentRecord> getCurrentAssignment(@NonNull UUID workorderId);

    @NonNull
    List<TechnicianAssignmentRecord> getAssignmentHistory(@NonNull UUID workorderId);

    @NonNull
    Optional<UUID> getPreviousTechnicianId(@NonNull UUID workorderId);

    @NonNull
    WorkorderStatus getWorkorderStatus(@NonNull UUID workorderId);
}

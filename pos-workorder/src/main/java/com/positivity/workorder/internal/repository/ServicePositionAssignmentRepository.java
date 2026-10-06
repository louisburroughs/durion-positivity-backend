package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.ServicePositionAssignment;
import com.positivity.workorder.internal.enums.ResourceType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Append-only service-position history for a workorder (#1983).
 *
 * <p>Deliberately the same three-method shape as {@link TechnicianAssignmentRepository}: current
 * row, full history newest-first, and a batch lookup of current rows for a set of workorders.
 */
public interface ServicePositionAssignmentRepository extends JpaRepository<ServicePositionAssignment, Long> {

    @NonNull
    Optional<ServicePositionAssignment> findByWorkorder_IdAndCurrentTrue(@NonNull UUID workorderId);

    @NonNull
    List<ServicePositionAssignment> findByWorkorder_IdOrderByAssignedAtDescIdDesc(@NonNull UUID workorderId);

    /** One history row, as the fact publisher needs it: no entity, so no LAZY workorder proxy. */
    interface PositionInterval {
        UUID getWorkorderId();

        ResourceType getResourceType();

        UUID getResourceId();

        UUID getLocationId();

        LocalDateTime getAssignedAt();

        LocalDateTime getReleasedAt();
    }

    /**
     * The whole position history of each listed workorder, oldest first (#2530).
     *
     * <p>The workorder fact publishes it as a replacement set, because the workorder row itself only
     * says where the job is now and is cleared when the job closes ({@code releaseOnClose}). One
     * query for the whole pending set, selecting columns rather than entities for the reason {@link
     * TechnicianAssignmentRepository#findCurrentTechnicians} gives.
     */
    @Query("SELECT p.workorder.id AS workorderId, p.resourceType AS resourceType, p.resourceId AS resourceId,"
            + " p.locationId AS locationId, p.assignedAt AS assignedAt, p.releasedAt AS releasedAt"
            + " FROM ServicePositionAssignment p WHERE p.workorder.id IN :workorderIds"
            + " ORDER BY p.assignedAt ASC, p.id ASC")
    @NonNull
    List<PositionInterval> findHistory(@Param("workorderIds") @NonNull Collection<UUID> workorderIds);
}

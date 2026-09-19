package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.WorkOrderAppointmentMapping;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkOrderAppointmentMappingRepository extends JpaRepository<WorkOrderAppointmentMapping, UUID> {

    Optional<WorkOrderAppointmentMapping> findByWorkOrderId(UUID workOrderId);

    /**
     * Resolves the workorder actual-time block for a batch of appointments in one query (#2021
     * AC1-AC3, AC4/AC5 carry-over) — the caller must not loop {@link #findByWorkOrderId} per
     * appointment, which would turn a bounded capacity read back into a per-day (here, per-
     * appointment) fan-out (#2023 AC7).
     *
     * <p>{@code ExtWorkorderReplica} carries no foreign key to {@link WorkOrderAppointmentMapping}
     * — the two are unrelated replicas of separate facts within this module's own schema — so the
     * join is expressed ad hoc on the shared workorder id rather than through a mapped association.
     * An appointment with no linked workorder, or one whose workorder has not yet replicated,
     * simply has no row in the result.
     *
     * <p>Because the join is an inner one, an appointment with several mappings (#2023 S1 — a
     * reopened work order adds a row without deleting the earlier one) yields only the mappings
     * whose replica has arrived. When the newest mapping is still in flight, the rows returned
     * describe the superseded run, and {@link WorkorderActuals#mostCurrent} reduces them to the
     * newest <em>replicated</em> mapping rather than to the newest mapping. That fallback is the
     * recorded choice of issue #2089 (option 1); {@code mostCurrent}'s javadoc states why, and
     * every caller of this method inherits it.
     */
    @Query("""
            SELECT new com.positivity.shopmanager.internal.repository.WorkorderActuals(
                       m.appointment.appointmentId, m.workOrderId, w.workStartedAt, w.completedAt, w.expectedEndAt)
            FROM WorkOrderAppointmentMapping m
            JOIN ExtWorkorderReplica w ON w.workorderId = m.workOrderId
            WHERE m.appointment.appointmentId IN :appointmentIds
            """)
    @NonNull
    List<WorkorderActuals> findActualsByAppointmentIds(
            @Param("appointmentIds") @NonNull Collection<UUID> appointmentIds);
}

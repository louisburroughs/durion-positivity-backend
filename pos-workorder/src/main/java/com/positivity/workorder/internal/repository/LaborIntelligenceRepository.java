package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.WorkorderLaborEntry;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Reads for the labor-intelligence rollup (#1575 Tier 0 / Tier 4 sketch, T0-5). Two projections
 * rather than one, because the shop median and the technician median are different questions:
 * the shop's is per finished service line however many technicians touched it, the technician's
 * is per technician on that line.
 *
 * <p>Deliberately its own repository interface rather than more methods on
 * {@code WorkorderLaborEntryRepository}: these are analytics projections with no entity to
 * return, and mixing them into the operational repository invites someone to reach for a
 * whole-table aggregate on a request path.
 */
public interface LaborIntelligenceRepository extends JpaRepository<WorkorderLaborEntry, UUID> {

    /**
     * One row per finished service line that has both a guide baseline and clocked time:
     * {@code [workorderServiceId, serviceEntityId, locationId, guideHours, actualHours]}.
     *
     * <p>Only lines carrying {@code guideHours} count. A line with no baseline contributes an
     * actual with nothing to compare it against, and averaging those in would quietly move the
     * variance without changing any real estimate.
     */
    @Query("""
            SELECT ws.id, ws.serviceEntityId, w.locationId, ws.guideHours, SUM(e.hoursWorked)
            FROM WorkorderLaborEntry e
            JOIN e.workorderService ws
            JOIN e.workorder w
            WHERE e.endTime IS NOT NULL
              AND e.hoursWorked IS NOT NULL
              AND ws.serviceEntityId IS NOT NULL
              AND ws.guideHours IS NOT NULL
            GROUP BY ws.id, ws.serviceEntityId, w.locationId, ws.guideHours
            """)
    @NonNull
    List<Object[]> findLineTotals();

    /**
     * One row per (service line, technician):
     * {@code [workorderServiceId, serviceEntityId, technicianId, actualHours]}.
     *
     * <p>The line id rides along so the caller can tell a line one technician finished from a
     * line two split — the second says nothing about either one's speed.
     */
    @Query("""
            SELECT ws.id, ws.serviceEntityId, e.technicianId, SUM(e.hoursWorked)
            FROM WorkorderLaborEntry e
            JOIN e.workorderService ws
            WHERE e.endTime IS NOT NULL
              AND e.hoursWorked IS NOT NULL
              AND e.technicianId IS NOT NULL
              AND ws.serviceEntityId IS NOT NULL
              AND ws.guideHours IS NOT NULL
            GROUP BY ws.id, ws.serviceEntityId, e.technicianId
            """)
    @NonNull
    List<Object[]> findLineTotalsByTechnician();
}

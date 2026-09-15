package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.dto.AssignmentUpdatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Consumes pos-shop-manager's {@link com.positivity.workorder.internal.dto.AssignmentUpdatedEvent}
 * and delegates to {@link WorkorderService#handleAssignmentUpdated} (CAP:140 Story #64).
 *
 * <p>The two modules sit on opposite sides of the assignment-ownership boundary (#2000).
 * pos-shop-manager owns the <em>planned</em> assignment — who is expected to work an appointment,
 * including whatever multi-mechanic shape ({@code AssignmentMechanic} with a {@code LEAD}/{@code
 * ASSIST} role) it keeps for scheduling. pos-workorder owns the <em>actual current</em> technician
 * of record on the workorder, and it is exactly one person (#1985, #1990): one technician per
 * workorder, one technician per mobile unit, no crew, no roles.
 *
 * <p>{@code AssignmentUpdatedEvent} is therefore an input to this module, never a second system of
 * record for who is currently on the job. A multi-mechanic planned assignment does not become
 * multiple technicians here — {@link WorkorderService#handleAssignmentUpdated} only ever updates
 * the workorder's position context ({@code locationId}, {@code resourceId}, {@code resourceType}),
 * never {@code TechnicianAssignment}. pos-shop-manager's {@code AssignmentMechanic(LEAD|ASSIST)}
 * shape stops at that boundary and is not mirrored into pos-workorder; the current technician is
 * set and changed only through {@link TechnicianAssignmentService#assignTechnician} and {@link
 * TechnicianAssignmentService#reassignTechnician}, which this listener never calls.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkorderAssignmentEventListener {

    private final WorkorderService workorderService;

    @EventListener
    public void onAssignmentUpdated(AssignmentUpdatedEvent event) {
        log.info("Received AssignmentUpdatedEvent for workorderId={}", event.getWorkorderId());
        workorderService.handleAssignmentUpdated(event);
    }
}

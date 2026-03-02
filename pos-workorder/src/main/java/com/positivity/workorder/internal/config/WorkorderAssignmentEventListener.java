package com.positivity.workorder.internal.config;

import com.positivity.workorder.internal.dto.AssignmentUpdatedEvent;
import com.positivity.workorder.service.WorkorderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Listens for AssignmentUpdated events from the shop management service
 * and delegates assignment context updates to WorkorderService.
 * CAP:140 Story #64.
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

package com.positivity.workorder.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.dto.ReassignTechnicianRequest;
import com.positivity.workorder.internal.dto.TechnicianAssignmentRecord;
import com.positivity.workorder.internal.dto.TechnicianAssignmentResponse;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.service.TechnicianAssignmentService;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * The reassignment response names the technician it replaced, which is only the second-newest
 * assignment once the reassignment has been recorded.
 */
class TechnicianAssignmentControllerReassignTest {

    private static final UUID WORKORDER_ID = UUID.fromString("01a0a52c-89f9-7ef9-9c97-583da36fa240");
    private static final UUID OLD_TECHNICIAN_ID = UUID.fromString("01a0a52c-89f9-7ef9-9c97-583da36fa250");
    private static final UUID NEW_TECHNICIAN_ID = UUID.fromString("01a0a52c-89f9-7ef9-9c97-583da36fa260");

    private final TechnicianAssignmentService service = mock(TechnicianAssignmentService.class);
    private final TechnicianAssignmentController controller = new TechnicianAssignmentController(service);

    @Test
    void reassign_resolvesPreviousTechnicianAfterTheReassignment() {
        TechnicianAssignmentRecord newAssignment = new TechnicianAssignmentRecord(
                2L,
                WORKORDER_ID,
                NEW_TECHNICIAN_ID,
                LocalDateTime.of(2026, 10, 5, 9, 0),
                "system",
                null,
                "Scheduling conflict",
                null,
                true);
        when(service.reassignTechnician(eq(WORKORDER_ID), eq(NEW_TECHNICIAN_ID), anyString(), any(), any()))
                .thenReturn(newAssignment);
        when(service.getPreviousTechnicianId(WORKORDER_ID)).thenReturn(Optional.of(OLD_TECHNICIAN_ID));
        when(service.getWorkorderStatus(WORKORDER_ID)).thenReturn(WorkorderStatus.ASSIGNED);
        when(service.resolveTechnicianNames(anyCollection()))
                .thenReturn(Map.of(NEW_TECHNICIAN_ID, "Nina New", OLD_TECHNICIAN_ID, "Oscar Old"));

        ReassignTechnicianRequest request = ReassignTechnicianRequest.builder()
                .newTechnicianId(NEW_TECHNICIAN_ID)
                .reason("Scheduling conflict")
                .build();

        TechnicianAssignmentResponse body =
                controller.reassignTechnician(WORKORDER_ID, request, null).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getTechnicianName()).isEqualTo("Nina New");
        assertThat(body.getPreviousTechnicianId()).isEqualTo(OLD_TECHNICIAN_ID.toString());
        assertThat(body.getPreviousTechnicianName()).isEqualTo("Oscar Old");

        InOrder order = inOrder(service);
        order.verify(service).reassignTechnician(eq(WORKORDER_ID), eq(NEW_TECHNICIAN_ID), anyString(), any(), any());
        order.verify(service).getPreviousTechnicianId(WORKORDER_ID);
    }
}

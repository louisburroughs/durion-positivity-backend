package com.positivity.people.controller;

import com.positivity.people.internal.controller.TimeEntryAdjustmentController;
import com.positivity.people.internal.dto.TimeEntryAdjustment;
import com.positivity.people.internal.dto.TimeEntryAdjustmentRequest;
import com.positivity.people.internal.dto.TimeEntryAdjustmentResponse;
import com.positivity.people.service.TimeEntryAdjustmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TimeEntryAdjustmentControllerTest {

    private TimeEntryAdjustmentService service;
    private TimeEntryAdjustmentController controller;

    @BeforeEach
    void setup() {
        service = mock(TimeEntryAdjustmentService.class);
        controller = new TimeEntryAdjustmentController(service);
    }

    @Test
    void listForTimeEntry_returnsList() {
        UUID timeEntryId = UUID.randomUUID();
        TimeEntryAdjustment a = new TimeEntryAdjustment();
        a.setAdjustmentId(UUID.randomUUID());
        a.setTimeEntryId(timeEntryId.toString());
        when(service.listForTimeEntry(timeEntryId.toString())).thenReturn(List.of(a));

        ResponseEntity<List<TimeEntryAdjustment>> resp = controller.listForTimeEntry(timeEntryId.toString());
        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertEquals(1, resp.getBody().size());
    }

    @Test
    void createAdjustment_success_returnsOk() {
        UUID timeEntryId = UUID.randomUUID();
        TimeEntryAdjustmentRequest req = new TimeEntryAdjustmentRequest();
        req.setTimeEntryId(timeEntryId.toString());
        req.setReasonCode("RC1");
        req.setMinutesDelta(15);
        req.setCreatedBy("tester");

        TimeEntryAdjustmentResponse created = new TimeEntryAdjustmentResponse(UUID.randomUUID(), true, "created");
        when(service.createAdjustment(req)).thenReturn(created);

        ResponseEntity<TimeEntryAdjustmentResponse> resp = controller.createAdjustment(req);
        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().isSuccess());
        assertNotNull(resp.getBody().getAdjustmentId());
    }

    @Test
    void createAdjustment_failure_returnsBadRequest() {
        UUID timeEntryId = UUID.randomUUID();
        TimeEntryAdjustmentRequest req = new TimeEntryAdjustmentRequest();
        req.setTimeEntryId(timeEntryId.toString());
        req.setReasonCode(" ");

        TimeEntryAdjustmentResponse invalid = new TimeEntryAdjustmentResponse(null, false, "reasonCode is required");
        when(service.createAdjustment(req)).thenReturn(invalid);

        ResponseEntity<TimeEntryAdjustmentResponse> resp = controller.createAdjustment(req);
        assertEquals(400, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertFalse(resp.getBody().isSuccess());
    }
}

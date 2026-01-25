package com.positivity.people.controller;

import com.positivity.people.entity.TimeEntryAdjustment;
import com.positivity.people.repository.TimeEntryAdjustmentRepository;
import com.positivity.people.service.TimeEntryAdjustmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class TimeEntryAdjustmentControllerTest {

    private TimeEntryAdjustmentRepository repo;
    private TimeEntryAdjustmentService service;
    private TimeEntryAdjustmentController controller;

    @BeforeEach
    public void setup() {
        repo = mock(TimeEntryAdjustmentRepository.class);
        service = mock(TimeEntryAdjustmentService.class);
        controller = new TimeEntryAdjustmentController(repo, service);
    }

    @Test
    public void listForTimeEntry_returnsList() {
        TimeEntryAdjustment a = new TimeEntryAdjustment();
        a.setAdjustmentId(UUID.randomUUID());
        a.setTimeEntryId("T1");
        when(repo.findByTimeEntryId("T1")).thenReturn(List.of(a));

        ResponseEntity<List<TimeEntryAdjustment>> resp = controller.listForTimeEntry("T1");
        assertEquals(200, resp.getStatusCodeValue());
        assertNotNull(resp.getBody());
        assertEquals(1, resp.getBody().size());
    }

    @Test
    public void createAdjustment_withMinutesDelta_succeeds() {
        when(repo.save(any())).thenAnswer(inv -> {
            TimeEntryAdjustment in = inv.getArgument(0);
            in.setAdjustmentId(UUID.randomUUID());
            return in;
        });

        com.positivity.people.dto.TimeEntryAdjustmentRequest req = new com.positivity.people.dto.TimeEntryAdjustmentRequest();
        req.setTimeEntryId("T1");
        req.setReasonCode("RC1");
        req.setMinutesDelta(15);
        req.setCreatedBy("tester");

        ResponseEntity<com.positivity.people.dto.TimeEntryAdjustmentResponse> resp = controller.createAdjustment(req);
        assertEquals(200, resp.getStatusCodeValue());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().isSuccess());
        assertNotNull(resp.getBody().getAdjustmentId());
    }

    @Test
    public void createAdjustment_withProposedTimes_succeeds() {
        when(repo.save(any())).thenAnswer(inv -> {
            TimeEntryAdjustment in = inv.getArgument(0);
            in.setAdjustmentId(UUID.randomUUID());
            return in;
        });

        com.positivity.people.dto.TimeEntryAdjustmentRequest req = new com.positivity.people.dto.TimeEntryAdjustmentRequest();
        req.setTimeEntryId("T2");
        req.setReasonCode("RC2");
        req.setProposedStartAt(OffsetDateTime.now());
        req.setProposedEndAt(OffsetDateTime.now().plusMinutes(30));
        req.setCreatedBy("tester");

        ResponseEntity<com.positivity.people.dto.TimeEntryAdjustmentResponse> resp = controller.createAdjustment(req);
        assertEquals(200, resp.getStatusCodeValue());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().isSuccess());
    }

    @Test
    public void createAdjustment_withBothMinutesAndTimes_returnsBadRequest() {
        com.positivity.people.dto.TimeEntryAdjustmentRequest req = new com.positivity.people.dto.TimeEntryAdjustmentRequest();
        req.setTimeEntryId("T3");
        req.setReasonCode("RC3");
        req.setMinutesDelta(10);
        req.setProposedStartAt(OffsetDateTime.now());
        req.setProposedEndAt(OffsetDateTime.now().plusMinutes(10));

        ResponseEntity<com.positivity.people.dto.TimeEntryAdjustmentResponse> resp = controller.createAdjustment(req);
        assertEquals(400, resp.getStatusCodeValue());
        assertNotNull(resp.getBody());
        assertFalse(resp.getBody().isSuccess());
    }

    @Test
    public void createAdjustment_withNeitherMinutesNorTimes_returnsBadRequest() {
        com.positivity.people.dto.TimeEntryAdjustmentRequest req = new com.positivity.people.dto.TimeEntryAdjustmentRequest();
        req.setTimeEntryId("T4");
        req.setReasonCode("RC4");

        ResponseEntity<com.positivity.people.dto.TimeEntryAdjustmentResponse> resp = controller.createAdjustment(req);
        assertEquals(400, resp.getStatusCodeValue());
        assertNotNull(resp.getBody());
        assertFalse(resp.getBody().isSuccess());
    }
}

package com.positivity.people.controller;

import com.positivity.people.internal.controller.TimeEntryAdjustmentController;
import com.positivity.people.internal.entity.TimeEntryAdjustment;
import com.positivity.people.internal.repository.TimeEntryAdjustmentRepository;
import com.positivity.people.service.TimeEntryAdjustmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    private com.positivity.people.internal.repository.TimeEntryRepository timeEntryRepo;

    @BeforeEach
    public void setup() {
        repo = mock(TimeEntryAdjustmentRepository.class);
        service = mock(TimeEntryAdjustmentService.class);
        timeEntryRepo = mock(com.positivity.people.internal.repository.TimeEntryRepository.class);
        controller = new TimeEntryAdjustmentController(repo, service, timeEntryRepo);
    }

    @Test
    public void listForTimeEntry_returnsList() {
        TimeEntryAdjustment a = new TimeEntryAdjustment();
        a.setAdjustmentId(UUID.randomUUID());
        a.setTimeEntryId("T1");
        when(repo.findByTimeEntryId("T1")).thenReturn(List.of(a));

        ResponseEntity<List<TimeEntryAdjustment>> resp = controller.listForTimeEntry("T1");
        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertEquals(1, resp.getBody().size());
    }

    @Test
    public void createAdjustment_withMinutesDelta_succeeds() {
        // Mock time entry lookup
        com.positivity.people.internal.entity.TimeEntry te = new com.positivity.people.internal.entity.TimeEntry();
        te.setTimeEntryId("T1");
        te.setStatus(com.positivity.people.internal.model.TimeEntryStatus.PENDING_APPROVAL);
        when(timeEntryRepo.findById("T1")).thenReturn(java.util.Optional.of(te));

        when(repo.save(any())).thenAnswer(inv -> {
            TimeEntryAdjustment in = inv.getArgument(0);
            in.setAdjustmentId(UUID.randomUUID());
            return in;
        });

        com.positivity.people.internal.dto.TimeEntryAdjustmentRequest req = new com.positivity.people.internal.dto.TimeEntryAdjustmentRequest();
        req.setTimeEntryId("T1");
        req.setReasonCode("RC1");
        req.setMinutesDelta(15);
        req.setCreatedBy("tester");

        ResponseEntity<com.positivity.people.internal.dto.TimeEntryAdjustmentResponse> resp = controller
                .createAdjustment(req);
        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().isSuccess());
        assertNotNull(resp.getBody().getAdjustmentId());
    }

    @Test
    public void createAdjustment_withProposedTimes_succeeds() {
        // Mock time entry lookup
        com.positivity.people.internal.entity.TimeEntry te = new com.positivity.people.internal.entity.TimeEntry();
        te.setTimeEntryId("T2");
        te.setStatus(com.positivity.people.internal.model.TimeEntryStatus.PENDING_APPROVAL);
        when(timeEntryRepo.findById("T2")).thenReturn(java.util.Optional.of(te));

        when(repo.save(any())).thenAnswer(inv -> {
            TimeEntryAdjustment in = inv.getArgument(0);
            in.setAdjustmentId(UUID.randomUUID());
            return in;
        });

        com.positivity.people.internal.dto.TimeEntryAdjustmentRequest req = new com.positivity.people.internal.dto.TimeEntryAdjustmentRequest();
        req.setTimeEntryId("T2");
        req.setReasonCode("RC2");
        req.setProposedStartAt(OffsetDateTime.now());
        req.setProposedEndAt(OffsetDateTime.now().plusMinutes(30));
        req.setCreatedBy("tester");

        ResponseEntity<com.positivity.people.internal.dto.TimeEntryAdjustmentResponse> resp = controller
                .createAdjustment(req);
        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().isSuccess());
    }

    @Test
    public void createAdjustment_withBothMinutesAndTimes_returnsBadRequest() {
        // Mock time entry lookup
        com.positivity.people.internal.entity.TimeEntry te = new com.positivity.people.internal.entity.TimeEntry();
        te.setTimeEntryId("T3");
        te.setStatus(com.positivity.people.internal.model.TimeEntryStatus.PENDING_APPROVAL);
        when(timeEntryRepo.findById("T3")).thenReturn(java.util.Optional.of(te));

        com.positivity.people.internal.dto.TimeEntryAdjustmentRequest req = new com.positivity.people.internal.dto.TimeEntryAdjustmentRequest();
        req.setTimeEntryId("T3");
        req.setReasonCode("RC3");
        req.setMinutesDelta(10);
        req.setProposedStartAt(OffsetDateTime.now());
        req.setProposedEndAt(OffsetDateTime.now().plusMinutes(10));

        ResponseEntity<com.positivity.people.internal.dto.TimeEntryAdjustmentResponse> resp = controller
                .createAdjustment(req);
        assertEquals(400, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertFalse(resp.getBody().isSuccess());
    }

    @Test
    public void createAdjustment_withNeitherMinutesNorTimes_returnsBadRequest() {
        // Mock time entry lookup
        com.positivity.people.internal.entity.TimeEntry te = new com.positivity.people.internal.entity.TimeEntry();
        te.setTimeEntryId("T4");
        te.setStatus(com.positivity.people.internal.model.TimeEntryStatus.PENDING_APPROVAL);
        when(timeEntryRepo.findById("T4")).thenReturn(java.util.Optional.of(te));

        com.positivity.people.internal.dto.TimeEntryAdjustmentRequest req = new com.positivity.people.internal.dto.TimeEntryAdjustmentRequest();
        req.setTimeEntryId("T4");
        req.setReasonCode("RC4");

        ResponseEntity<com.positivity.people.internal.dto.TimeEntryAdjustmentResponse> resp = controller
                .createAdjustment(req);
        assertEquals(400, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertFalse(resp.getBody().isSuccess());
    }

    @Test
    public void createAdjustment_withNonexistentTimeEntry_returnsNotFound() {
        when(timeEntryRepo.findById("NONEXISTENT")).thenReturn(java.util.Optional.empty());

        com.positivity.people.internal.dto.TimeEntryAdjustmentRequest req = new com.positivity.people.internal.dto.TimeEntryAdjustmentRequest();
        req.setTimeEntryId("NONEXISTENT");
        req.setReasonCode("RC5");
        req.setMinutesDelta(10);

        ResponseEntity<com.positivity.people.internal.dto.TimeEntryAdjustmentResponse> resp = controller
                .createAdjustment(req);
        assertEquals(404, resp.getStatusCode().value());
    }

    @Test
    public void createAdjustment_withNonPendingApprovalStatus_returnsInvalidState() {
        // Mock time entry with APPROVED status (not PENDING_APPROVAL)
        com.positivity.people.internal.entity.TimeEntry te = new com.positivity.people.internal.entity.TimeEntry();
        te.setTimeEntryId("T5");
        te.setStatus(com.positivity.people.internal.model.TimeEntryStatus.APPROVED);
        when(timeEntryRepo.findById("T5")).thenReturn(java.util.Optional.of(te));

        com.positivity.people.internal.dto.TimeEntryAdjustmentRequest req = new com.positivity.people.internal.dto.TimeEntryAdjustmentRequest();
        req.setTimeEntryId("T5");
        req.setReasonCode("RC6");
        req.setMinutesDelta(10);

        ResponseEntity<com.positivity.people.internal.dto.TimeEntryAdjustmentResponse> resp = controller
                .createAdjustment(req);
        assertEquals(422, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertFalse(resp.getBody().isSuccess());
    }

    @Test
    public void createAdjustment_withoutReasonCode_returnsBadRequest() {
        com.positivity.people.internal.dto.TimeEntryAdjustmentRequest req = new com.positivity.people.internal.dto.TimeEntryAdjustmentRequest();
        req.setTimeEntryId("T5");
        req.setMinutesDelta(10);
        req.setCreatedBy("tester");

        ResponseEntity<com.positivity.people.internal.dto.TimeEntryAdjustmentResponse> resp = controller
                .createAdjustment(req);
        assertEquals(400, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertFalse(resp.getBody().isSuccess());
    }

    @Test
    public void createAdjustment_withBlankReasonCode_returnsBadRequest() {
        com.positivity.people.internal.dto.TimeEntryAdjustmentRequest req = new com.positivity.people.internal.dto.TimeEntryAdjustmentRequest();
        req.setTimeEntryId("T6");
        req.setReasonCode("   ");
        req.setMinutesDelta(10);
        req.setCreatedBy("tester");

        ResponseEntity<com.positivity.people.internal.dto.TimeEntryAdjustmentResponse> resp = controller
                .createAdjustment(req);
        assertEquals(400, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertFalse(resp.getBody().isSuccess());
    }
}

package com.positivity.people.controller;

import com.positivity.people.internal.controller.TimeEntryApprovalController;
import com.positivity.people.internal.dto.TimeEntryDecisionBatchRequest;
import com.positivity.people.internal.entity.TimeEntry;
import com.positivity.people.internal.repository.TimeEntryRepository;
import com.positivity.people.internal.repository.TimeEntryAuditRepository;
import com.positivity.people.service.TimeEntryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class TimeEntryBatchIntegrationTest {

    private TimeEntryRepository entryRepository;
    private TimeEntryAuditRepository auditRepository;
    private TimeEntryService timeEntryService;
    private TimeEntryApprovalController controller;

    @BeforeEach
    public void setup() {
        entryRepository = mock(TimeEntryRepository.class);
        auditRepository = mock(TimeEntryAuditRepository.class);
        timeEntryService = new TimeEntryService(entryRepository, auditRepository);
        controller = new TimeEntryApprovalController(timeEntryService);
    }

    @Test
    public void approveTimeEntries_withValidEntries_succeeds() {
        java.util.UUID entryId1 = java.util.UUID.randomUUID();
        java.util.UUID entryId2 = java.util.UUID.randomUUID();

        TimeEntry entry1 = new TimeEntry();
        entry1.setTimeEntryId(entryId1);
        entry1.setStatus(com.positivity.people.internal.model.TimeEntryStatus.PENDING_APPROVAL);

        TimeEntry entry2 = new TimeEntry();
        entry2.setTimeEntryId(entryId2);
        entry2.setStatus(com.positivity.people.internal.model.TimeEntryStatus.PENDING_APPROVAL);

        when(entryRepository.findById(entryId1)).thenReturn(Optional.of(entry1));
        when(entryRepository.findById(entryId2)).thenReturn(Optional.of(entry2));
        when(entryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        TimeEntryDecisionBatchRequest request = new TimeEntryDecisionBatchRequest();
        TimeEntryDecisionBatchRequest.Decision d1 = new TimeEntryDecisionBatchRequest.Decision();
        d1.setTimeEntryId(entryId1.toString());
        TimeEntryDecisionBatchRequest.Decision d2 = new TimeEntryDecisionBatchRequest.Decision();
        d2.setTimeEntryId(entryId2.toString());
        request.setDecisions(Arrays.asList(d1, d2));

        ResponseEntity<?> response = controller.approveTimeEntries(request, "approver1", null, "cid-001");
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    public void rejectTimeEntries_withoutReasonForEntry_returnsBadRequest() {
        TimeEntryDecisionBatchRequest request = new TimeEntryDecisionBatchRequest();
        TimeEntryDecisionBatchRequest.Decision d1 = new TimeEntryDecisionBatchRequest.Decision();
        d1.setTimeEntryId(java.util.UUID.randomUUID().toString());
        d1.setRejectionReason(null);
        request.setDecisions(Arrays.asList(d1));

        ResponseEntity<?> response = controller.rejectTimeEntries(request, "approver1", null, "cid-002");
        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    public void rejectTimeEntries_withValidReasons_succeeds() {
        java.util.UUID entryId = java.util.UUID.randomUUID();
        TimeEntry entry = new TimeEntry();
        entry.setTimeEntryId(entryId);
        entry.setStatus(com.positivity.people.internal.model.TimeEntryStatus.PENDING_APPROVAL);

        when(entryRepository.findById(entryId)).thenReturn(Optional.of(entry));
        when(entryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        TimeEntryDecisionBatchRequest request = new TimeEntryDecisionBatchRequest();
        TimeEntryDecisionBatchRequest.Decision d1 = new TimeEntryDecisionBatchRequest.Decision();
        d1.setTimeEntryId(entryId.toString());
        d1.setRejectionReason("Time discrepancy");
        request.setDecisions(Arrays.asList(d1));

        ResponseEntity<?> response = controller.rejectTimeEntries(request, "approver1", null, "cid-003");
        assertEquals(200, response.getStatusCode().value());
    }
}

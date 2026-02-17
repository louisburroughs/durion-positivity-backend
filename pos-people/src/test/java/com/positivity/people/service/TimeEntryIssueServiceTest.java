package com.positivity.people.service;

import com.positivity.people.internal.entity.TimeEntryIssue;
import com.positivity.people.internal.entity.TimeEntryAudit;
import com.positivity.people.internal.repository.TimeEntryIssueRepository;
import com.positivity.people.internal.repository.TimeEntryAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class TimeEntryIssueServiceTest {

    private TimeEntryIssueRepository issueRepository;
    private TimeEntryAuditRepository auditRepository;
    private TimeEntryIssueService service;

    @BeforeEach
    public void setup() {
        issueRepository = mock(TimeEntryIssueRepository.class);
        auditRepository = mock(TimeEntryAuditRepository.class);
        service = new TimeEntryIssueService(issueRepository, auditRepository);
    }

    @Test
    public void resolveException_withPermission_succeeds() {
        UUID id = UUID.randomUUID();
        TimeEntryIssue ex = new TimeEntryIssue();
        ex.setIssueId(id);
        ex.setTimeEntryId("TE-3");
        ex.setStatus(com.positivity.people.internal.enums.ExceptionStatus.OPEN);

        when(issueRepository.findById(id)).thenReturn(Optional.of(ex));
        when(issueRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        boolean ok = service.resolveException(id, "resolver1", Set.of("people:timeException:resolve"), "note",
                "RESOLVED", "cid-3");
        assertTrue(ok);

        ArgumentCaptor<TimeEntryIssue> captor = ArgumentCaptor.forClass(TimeEntryIssue.class);
        verify(issueRepository).save(captor.capture());
        TimeEntryIssue saved = captor.getValue();
        assertEquals(com.positivity.people.internal.enums.ExceptionStatus.RESOLVED, saved.getStatus());
        assertEquals("resolver1", saved.getResolvedBy());

        verify(auditRepository).save(any(TimeEntryAudit.class));
    }

    @Test
    public void resolveException_withoutPermission_fails() {
        UUID id = UUID.randomUUID();
        TimeEntryIssue ex = new TimeEntryIssue();
        ex.setIssueId(id);
        ex.setTimeEntryId("TE-4");
        ex.setStatus(com.positivity.people.internal.enums.ExceptionStatus.OPEN);

        when(issueRepository.findById(id)).thenReturn(Optional.of(ex));

        boolean ok = service.resolveException(id, "user2", Set.of("people:timeEntry:approve"), null, "RESOLVED",
                "cid-4");
        assertFalse(ok);
        verify(issueRepository, never()).save(any());
        verify(auditRepository).save(any(TimeEntryAudit.class));
    }

    @Test
    public void actionException_acknowledge_succeeds() {
        UUID id = UUID.randomUUID();
        TimeEntryIssue ex = new TimeEntryIssue();
        ex.setIssueId(id);
        ex.setTimeEntryId("TE-5");
        ex.setStatus(com.positivity.people.internal.enums.ExceptionStatus.OPEN);

        when(issueRepository.findById(id)).thenReturn(Optional.of(ex));
        when(issueRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        boolean ok = service.actionException(id, com.positivity.people.internal.enums.ExceptionStatus.ACKNOWLEDGED,
                "user1",
                null, "cid-5");
        assertTrue(ok);

        ArgumentCaptor<TimeEntryIssue> captor = ArgumentCaptor.forClass(TimeEntryIssue.class);
        verify(issueRepository).save(captor.capture());
        assertEquals(com.positivity.people.internal.enums.ExceptionStatus.ACKNOWLEDGED, captor.getValue().getStatus());
    }

    @Test
    public void actionException_waive_withReason_succeeds() {
        UUID id = UUID.randomUUID();
        TimeEntryIssue ex = new TimeEntryIssue();
        ex.setIssueId(id);
        ex.setTimeEntryId("TE-6");
        ex.setStatus(com.positivity.people.internal.enums.ExceptionStatus.ACKNOWLEDGED);

        when(issueRepository.findById(id)).thenReturn(Optional.of(ex));
        when(issueRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        boolean ok = service.actionException(id, com.positivity.people.internal.enums.ExceptionStatus.WAIVED, "user2",
                "not applicable", "cid-6");
        assertTrue(ok);

        ArgumentCaptor<TimeEntryIssue> captor = ArgumentCaptor.forClass(TimeEntryIssue.class);
        verify(issueRepository).save(captor.capture());
        assertEquals(com.positivity.people.internal.enums.ExceptionStatus.WAIVED, captor.getValue().getStatus());
        assertEquals("not applicable", captor.getValue().getResolutionNotes());
    }

    @Test
    public void actionException_fromResolvedStatus_fails() {
        UUID id = UUID.randomUUID();
        TimeEntryIssue ex = new TimeEntryIssue();
        ex.setIssueId(id);
        ex.setStatus(com.positivity.people.internal.enums.ExceptionStatus.RESOLVED);

        when(issueRepository.findById(id)).thenReturn(Optional.of(ex));

        boolean ok = service.actionException(id, com.positivity.people.internal.enums.ExceptionStatus.WAIVED, "user3",
                null,
                "cid-7");
        assertFalse(ok);
        verify(issueRepository, never()).save(any());
    }
}

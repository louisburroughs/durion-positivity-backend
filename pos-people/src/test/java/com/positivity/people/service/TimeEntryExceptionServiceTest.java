package com.positivity.people.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.positivity.security.common.GatewaySecurityConstants;

import com.positivity.people.internal.entity.TimeEntryAudit;
import com.positivity.people.internal.entity.TimeEntryException;
import com.positivity.people.internal.repository.TimeEntryAuditRepository;
import com.positivity.people.internal.repository.TimeEntryExceptionRepository;
import com.positivity.people.internal.service.TimeEntryExceptionServiceImpl;

class TimeEntryExceptionServiceTest {

    private TimeEntryExceptionRepository exceptionRepository;
    private TimeEntryAuditRepository auditRepository;
    private TimeEntryExceptionService service;

    @BeforeEach
    void setup() {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(
                "test-user", "password", "ROLE_USER");
        authentication.setDetails(java.util.Map.of(
                GatewaySecurityConstants.DETAIL_USER_ID, "33333333-3333-3333-3333-333333333333",
                GatewaySecurityConstants.DETAIL_USERNAME, "test-user"));
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        exceptionRepository = mock(TimeEntryExceptionRepository.class);
        auditRepository = mock(TimeEntryAuditRepository.class);
        service = new TimeEntryExceptionServiceImpl(exceptionRepository, auditRepository);
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolveException_withPermission_succeeds() {
        UUID id = UUID.randomUUID();
        TimeEntryException ex = new TimeEntryException();
        ex.setExceptionId(id);
        ex.setTimeEntryId("TE-3");
        ex.setStatus(com.positivity.people.internal.enums.ExceptionStatus.OPEN);

        when(exceptionRepository.findById(id)).thenReturn(Optional.of(ex));
        when(exceptionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        boolean ok = service.resolveException(id, "resolver1", Set.of("people:timeException:resolve"), "note",
                "RESOLVED", "cid-3");
        assertTrue(ok);

        ArgumentCaptor<TimeEntryException> captor = ArgumentCaptor.forClass(TimeEntryException.class);
        verify(exceptionRepository).save(captor.capture());
        TimeEntryException saved = captor.getValue();
        assertEquals(com.positivity.people.internal.enums.ExceptionStatus.RESOLVED, saved.getStatus());
        assertEquals("resolver1", saved.getResolvedBy());

        verify(auditRepository).save(any(TimeEntryAudit.class));
    }

    @Test
    void resolveException_withoutPermission_fails() {
        UUID id = UUID.randomUUID();
        TimeEntryException ex = new TimeEntryException();
        ex.setExceptionId(id);
        ex.setTimeEntryId("TE-4");
        ex.setStatus(com.positivity.people.internal.enums.ExceptionStatus.OPEN);

        when(exceptionRepository.findById(id)).thenReturn(Optional.of(ex));

        boolean ok = service.resolveException(id, "user2", Set.of("people:timeEntry:approve"), null, "RESOLVED",
                "cid-4");
        assertFalse(ok);
        verify(exceptionRepository, never()).save(any());
        verify(auditRepository).save(any(TimeEntryAudit.class));
    }

    @Test
    void actionException_acknowledge_succeeds() {
        UUID id = UUID.randomUUID();
        TimeEntryException ex = new TimeEntryException();
        ex.setExceptionId(id);
        ex.setTimeEntryId("TE-5");
        ex.setStatus(com.positivity.people.internal.enums.ExceptionStatus.OPEN);

        when(exceptionRepository.findById(id)).thenReturn(Optional.of(ex));
        when(exceptionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        boolean ok = service.actionException(id, com.positivity.people.internal.enums.ExceptionStatus.ACKNOWLEDGED,
                "user1",
                null, "cid-5");
        assertTrue(ok);

        ArgumentCaptor<TimeEntryException> captor = ArgumentCaptor.forClass(TimeEntryException.class);
        verify(exceptionRepository).save(captor.capture());
        assertEquals(com.positivity.people.internal.enums.ExceptionStatus.ACKNOWLEDGED, captor.getValue().getStatus());
    }

    @Test
    void actionException_waive_withReason_succeeds() {
        UUID id = UUID.randomUUID();
        TimeEntryException ex = new TimeEntryException();
        ex.setExceptionId(id);
        ex.setTimeEntryId("TE-6");
        ex.setStatus(com.positivity.people.internal.enums.ExceptionStatus.ACKNOWLEDGED);

        when(exceptionRepository.findById(id)).thenReturn(Optional.of(ex));
        when(exceptionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        boolean ok = service.actionException(id, com.positivity.people.internal.enums.ExceptionStatus.WAIVED, "user2",
                "not applicable", "cid-6");
        assertTrue(ok);

        ArgumentCaptor<TimeEntryException> captor = ArgumentCaptor.forClass(TimeEntryException.class);
        verify(exceptionRepository).save(captor.capture());
        assertEquals(com.positivity.people.internal.enums.ExceptionStatus.WAIVED, captor.getValue().getStatus());
        assertEquals("not applicable", captor.getValue().getResolutionNotes());
    }

    @Test
    void actionException_fromResolvedStatus_fails() {
        UUID id = UUID.randomUUID();
        TimeEntryException ex = new TimeEntryException();
        ex.setExceptionId(id);
        ex.setStatus(com.positivity.people.internal.enums.ExceptionStatus.RESOLVED);

        when(exceptionRepository.findById(id)).thenReturn(Optional.of(ex));

        boolean ok = service.actionException(id, com.positivity.people.internal.enums.ExceptionStatus.WAIVED, "user3",
                null,
                "cid-7");
        assertFalse(ok);
        verify(exceptionRepository, never()).save(any());
    }
}

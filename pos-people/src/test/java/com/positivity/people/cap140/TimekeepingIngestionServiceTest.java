package com.positivity.people.cap140;

import com.positivity.people.internal.dto.WorkSessionCompletedEvent;
import com.positivity.people.internal.dto.WorkSessionCorrectedEvent;
import com.positivity.people.internal.entity.TimekeepingEntry;
import com.positivity.people.internal.enums.ApprovalStatus;
import com.positivity.people.internal.repository.TimekeepingEntryRepository;
import com.positivity.people.internal.service.TimekeepingIngestionServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TimekeepingIngestionServiceImpl} covering HR ingestion
 * of work sessions from shopmgr (CAP-140, Story #58).
 *
 * Issue: #58
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("java:S100")
class TimekeepingIngestionServiceTest {

    @Mock
    TimekeepingEntryRepository timekeepingEntryRepository;

    @InjectMocks
    TimekeepingIngestionServiceImpl timekeepingIngestionService;

    // -------------------------------------------------------------------------
    // AC1 – new session creates TimekeepingEntry with PENDING_APPROVAL
    // -------------------------------------------------------------------------

    @Test
    void ingestWorkSession_newEntry_createsEntryWithPendingApproval() {
        // Issue #58: AC1 — valid event, no existing entry → new
        // TimekeepingEntry(PENDING_APPROVAL)
        UUID tenantId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID workOrderId = UUID.randomUUID();
        Instant start = Instant.now().minusSeconds(3600);
        Instant end = Instant.now();

        WorkSessionCompletedEvent event = new WorkSessionCompletedEvent(
                tenantId, sessionId, employeeId, start, end, workOrderId);

        when(timekeepingEntryRepository.existsByTenantIdAndSourceSystemAndSourceSessionId(
                tenantId, "shopmgr", sessionId)).thenReturn(false);
        when(timekeepingEntryRepository.save(any(TimekeepingEntry.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        timekeepingIngestionService.ingestWorkSession(event);

        ArgumentCaptor<TimekeepingEntry> captor = ArgumentCaptor.forClass(TimekeepingEntry.class);
        verify(timekeepingEntryRepository).save(captor.capture());
        TimekeepingEntry saved = captor.getValue();

        assertThat(saved.getTenantId()).isEqualTo(tenantId);
        assertThat(saved.getSourceSessionId()).isEqualTo(sessionId);
        assertThat(saved.getEmployeeId()).isEqualTo(employeeId);
        assertThat(saved.getSourceSystem()).isEqualTo("shopmgr");
        assertThat(saved.getSessionStartTime()).isEqualTo(start);
        assertThat(saved.getSessionEndTime()).isEqualTo(end);
        assertThat(saved.getAssociatedWorkOrderId()).isEqualTo(workOrderId);
        assertThat(saved.getApprovalStatus()).isEqualTo(ApprovalStatus.PENDING_APPROVAL);
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // AC2 – duplicate event is idempotent: no persist, no modification
    // -------------------------------------------------------------------------

    @Test
    void ingestWorkSession_duplicateEvent_isIdempotentAndDoesNotPersist() {
        // Issue #58: AC2 — same (tenantId, sessionId) already in DB → no-op
        UUID tenantId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        WorkSessionCompletedEvent event = new WorkSessionCompletedEvent(
                tenantId, sessionId, UUID.randomUUID(),
                Instant.now().minusSeconds(100), Instant.now(), null);

        when(timekeepingEntryRepository.existsByTenantIdAndSourceSystemAndSourceSessionId(
                tenantId, "shopmgr", sessionId)).thenReturn(true);

        timekeepingIngestionService.ingestWorkSession(event);

        verify(timekeepingEntryRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // AC3 – null tenantId → IllegalArgumentException, no persist
    // -------------------------------------------------------------------------

    @Test
    void ingestWorkSession_nullTenantId_throwsIllegalArgumentException() {
        // Issue #58: AC3 — null tenantId simulates DLQ rejection
        WorkSessionCompletedEvent event = new WorkSessionCompletedEvent(
                null, UUID.randomUUID(), UUID.randomUUID(),
                Instant.now().minusSeconds(100), Instant.now(), null);

        assertThatThrownBy(() -> timekeepingIngestionService.ingestWorkSession(event))
                .isInstanceOf(IllegalArgumentException.class);

        verify(timekeepingEntryRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // AC4 – null sessionId → IllegalArgumentException, no persist
    // -------------------------------------------------------------------------

    @Test
    void ingestWorkSession_nullSessionId_throwsIllegalArgumentException() {
        // Issue #58: AC4 — null sessionId simulates DLQ rejection
        WorkSessionCompletedEvent event = new WorkSessionCompletedEvent(
                UUID.randomUUID(), null, UUID.randomUUID(),
                Instant.now().minusSeconds(100), Instant.now(), null);

        assertThatThrownBy(() -> timekeepingIngestionService.ingestWorkSession(event))
                .isInstanceOf(IllegalArgumentException.class);

        verify(timekeepingEntryRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // AC5 – correction event persists a supplemental TimekeepingEntry
    // -------------------------------------------------------------------------

    @Test
    void recordCorrection_validEvent_persistsCorrectionEntry() {
        // Issue #58: AC5 — WorkSessionCorrectedEvent → supplemental TimekeepingEntry
        // saved
        UUID tenantId = UUID.randomUUID();
        UUID originalSessionId = UUID.randomUUID();
        UUID correctionId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID workOrderId = UUID.randomUUID();
        String correctionReason = "Clock error correction";
        Instant start = Instant.now().minusSeconds(3600);
        Instant end = Instant.now();

        WorkSessionCorrectedEvent event = new WorkSessionCorrectedEvent(
                tenantId, originalSessionId, correctionId, employeeId,
                start, end, workOrderId, correctionReason);

        when(timekeepingEntryRepository.save(any(TimekeepingEntry.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        timekeepingIngestionService.recordCorrection(event);

        ArgumentCaptor<TimekeepingEntry> captor = ArgumentCaptor.forClass(TimekeepingEntry.class);
        verify(timekeepingEntryRepository).save(captor.capture());

        TimekeepingEntry saved = captor.getValue();
        assertThat(saved.getCorrectionId()).isEqualTo(correctionId);
        assertThat(saved.getCorrectionReason()).isEqualTo(correctionReason);
        assertThat(saved.getSourceSessionId()).isEqualTo(correctionId);
        assertThat(saved.getOriginalSourceSessionId()).isEqualTo(originalSessionId);
        assertThat(saved.getApprovalStatus()).isEqualTo(ApprovalStatus.PENDING_APPROVAL);
        assertThat(saved.getTenantId()).isEqualTo(tenantId);
    }
}

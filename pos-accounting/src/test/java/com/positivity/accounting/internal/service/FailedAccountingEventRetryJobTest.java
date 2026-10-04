package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.ReprocessEventRequest;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.ReprocessingAttemptHistory;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.enums.PostingFailureReason;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.accounting.internal.repository.ReprocessingAttemptHistoryRepository;
import com.positivity.tenancy.TenantIterator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class FailedAccountingEventRetryJobTest {

    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private static final UUID EVENT_ID = UUID.fromString("0199a000-0000-7000-8000-0000000000f1");
    private static final int MAX = 3;

    @Mock
    private AccountingEventRepository eventRepository;

    @Mock
    private ReprocessingAttemptHistoryRepository historyRepository;

    @Mock
    private EventIngestionService eventIngestionService;

    @Mock
    private TenantIterator tenantIterator;

    private FailedAccountingEventRetryJob job;

    @BeforeEach
    void setUp() {
        job = new FailedAccountingEventRetryJob(
                eventRepository,
                historyRepository,
                eventIngestionService,
                tenantIterator,
                mock(PlatformTransactionManager.class),
                Clock.fixed(NOW, ZoneOffset.UTC),
                MAX,
                25);
    }

    private AccountingEvent failed(int attempts) {
        AccountingEvent event = new AccountingEvent();
        event.setEventId(EVENT_ID);
        event.setStatus(AccountingEventStatus.FAILED);
        event.setAttemptCount(attempts);
        return event;
    }

    private void stubCandidates(UUID... ids) {
        when(eventRepository.findRetryCandidateIds(any(), anyInt(), any(), any(Pageable.class)))
                .thenReturn(List.of(ids));
    }

    @Test
    @DisplayName("each poll runs the bound-tenant retry once per active tenant")
    void retryFailed_runsPerTenant() {
        doAnswer(inv -> {
                    Consumer<UUID> work = inv.getArgument(0);
                    work.accept(UUID.randomUUID());
                    work.accept(UUID.randomUUID());
                    return 2;
                })
                .when(tenantIterator)
                .forEachActiveTenant(any());
        when(eventRepository.findRetryCandidateIds(any(), anyInt(), any(), any(Pageable.class)))
                .thenReturn(List.of());

        job.retryFailed();

        verify(eventRepository, org.mockito.Mockito.times(2))
                .findRetryCandidateIds(any(), eq(MAX), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("the candidate query is bounded by the batch size and excludes every non-retryable code")
    void candidateQuery_boundedAndExcludesCodes() {
        when(eventRepository.findRetryCandidateIds(any(), anyInt(), any(), any(Pageable.class)))
                .thenReturn(List.of());

        job.retryBoundTenant();

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<String>> excluded = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(eventRepository)
                .findRetryCandidateIds(
                        eq(List.of(AccountingEventStatus.FAILED, AccountingEventStatus.SUSPENDED)),
                        eq(MAX),
                        excluded.capture(),
                        page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(25);
        assertThat(Set.copyOf(excluded.getValue()))
                .containsExactlyInAnyOrder(
                        "DUPLICATE_CONFLICT",
                        "INVALID_PAYLOAD",
                        "VALIDATION_ERROR",
                        "MISSING_AMOUNT",
                        "PERIOD_CLOSED",
                        "CURRENCY_NOT_SUPPORTED");
        assertThat(PostingFailureReason.autoRetryExcludedCodes()).hasSize(6);
    }

    @Test
    @DisplayName("a claimed event is reprocessed as SYSTEM_RETRY_JOB")
    void claimedEvent_isReprocessed() {
        stubCandidates(EVENT_ID);
        AccountingEvent event = failed(0);
        when(eventRepository.findWithLockByEventIdAndStatusIn(eq(EVENT_ID), any()))
                .thenReturn(Optional.of(event));

        assertThat(job.retryBoundTenant()).isEqualTo(1);

        ArgumentCaptor<ReprocessEventRequest> request = ArgumentCaptor.forClass(ReprocessEventRequest.class);
        verify(eventIngestionService).reprocessEvent(eq(EVENT_ID), request.capture());
        assertThat(request.getValue().getTriggeredByUserId()).isEqualTo("SYSTEM_RETRY_JOB");
    }

    @Test
    @DisplayName("an event another instance holds, or already at the cap, is not retried")
    void unclaimedOrExhausted_notRetried() {
        stubCandidates(EVENT_ID);
        when(eventRepository.findWithLockByEventIdAndStatusIn(eq(EVENT_ID), any()))
                .thenReturn(Optional.empty());

        assertThat(job.retryBoundTenant()).isZero();

        when(eventRepository.findWithLockByEventIdAndStatusIn(eq(EVENT_ID), any()))
                .thenReturn(Optional.of(failed(MAX)));

        assertThat(job.retryBoundTenant()).isZero();
        verify(eventIngestionService, never()).reprocessEvent(any(), any());
    }

    @Test
    @DisplayName("a failing retry is recorded in a fresh transaction with a history row and does not stop the batch")
    void failure_recordedAndBatchContinues() {
        UUID second = UUID.fromString("0199a000-0000-7000-8000-0000000000f2");
        stubCandidates(EVENT_ID, second);
        AccountingEvent first = failed(1);
        AccountingEvent other = failed(0);
        other.setEventId(second);
        when(eventRepository.findWithLockByEventIdAndStatusIn(eq(EVENT_ID), any()))
                .thenReturn(Optional.of(first));
        when(eventRepository.findWithLockByEventIdAndStatusIn(eq(second), any()))
                .thenReturn(Optional.of(other));
        doThrow(new IllegalStateException("rollback-only"))
                .when(eventIngestionService)
                .reprocessEvent(eq(EVENT_ID), any());
        AccountingEvent fresh = failed(1);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(fresh));

        assertThat(job.retryBoundTenant()).isEqualTo(2);

        verify(eventIngestionService).reprocessEvent(eq(second), any());
        assertThat(fresh.getAttemptCount()).isEqualTo(2);
        assertThat(fresh.getStatus()).isEqualTo(AccountingEventStatus.FAILED);
        assertThat(fresh.getFailureReasonCode()).isEqualTo("INTERNAL_ERROR");
        ArgumentCaptor<ReprocessingAttemptHistory> history = ArgumentCaptor.forClass(ReprocessingAttemptHistory.class);
        verify(historyRepository).save(history.capture());
        assertThat(history.getValue().getTriggeredByUserId()).isEqualTo("SYSTEM_RETRY_JOB");
        assertThat(history.getValue().getAttemptedAt()).isEqualTo(NOW);
    }
}

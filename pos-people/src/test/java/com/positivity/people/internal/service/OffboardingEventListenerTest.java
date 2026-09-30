package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.entity.EmployeeOffboardingRetry;
import com.positivity.people.internal.enums.AssignmentTerminationPolicy;
import com.positivity.people.internal.event.EmployeeOffboardedEvent;
import com.positivity.people.internal.repository.EmployeeOffboardingRetryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/** After-commit offboarding handler (#2121). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OffboardingEventListener")
class OffboardingEventListenerTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID PERSON_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01");

    @Mock
    private OffboardingAssignmentEnder assignmentEnder;

    @Mock
    private EmployeeOffboardingRetryRepository retryRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    private OffboardingEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new OffboardingEventListener(assignmentEnder, retryRepository, CLOCK, transactionManager);
    }

    private static EmployeeOffboardedEvent event(AssignmentTerminationPolicy policy, LocalDate endDate) {
        return new EmployeeOffboardedEvent(PERSON_ID, policy, endDate, "Voluntary resignation", "hr.admin");
    }

    @Test
    @DisplayName("applies the policy from the event in its own transaction and queues nothing")
    void appliesThePolicy() {
        LocalDate endDate = LocalDate.of(2026, 3, 31);

        listener.onEmployeeOffboarded(event(AssignmentTerminationPolicy.GRACE_PERIOD, endDate));

        verify(assignmentEnder).apply(PERSON_ID, AssignmentTerminationPolicy.GRACE_PERIOD, endDate, "hr.admin");
        verify(retryRepository, never()).save(any());
        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    @DisplayName("a failure is swallowed and queued for retry in a second, independent transaction")
    void failureQueuesARetry() {
        LocalDate endDate = LocalDate.of(2026, 3, 31);
        doThrow(new IllegalStateException("outbox unavailable"))
                .when(assignmentEnder)
                .apply(any(), any(), any(), any());

        listener.onEmployeeOffboarded(event(AssignmentTerminationPolicy.GRACE_PERIOD, endDate));

        ArgumentCaptor<EmployeeOffboardingRetry> retry = ArgumentCaptor.forClass(EmployeeOffboardingRetry.class);
        verify(retryRepository).save(retry.capture());
        EmployeeOffboardingRetry row = retry.getValue();
        assertThat(row.getEmployeeId()).isEqualTo(PERSON_ID);
        assertThat(row.getAssignmentPolicy()).isEqualTo(AssignmentTerminationPolicy.GRACE_PERIOD);
        assertThat(row.getAssignmentEndDate()).isEqualTo(endDate);
        assertThat(row.getDisableReason()).isEqualTo("Voluntary resignation");
        assertThat(row.getActorId()).isEqualTo("hr.admin");
        assertThat(row.getFailureReason()).isEqualTo("outbox unavailable");
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(300));

        // Two transactions: the one the failure rolled back, and a separate one for the queue write.
        InOrder order = Mockito.inOrder(transactionManager);
        order.verify(transactionManager).getTransaction(any());
        order.verify(transactionManager).rollback(any());
        order.verify(transactionManager).getTransaction(any());
        order.verify(transactionManager).commit(any());
    }

    @Test
    @DisplayName("a failing queue write is swallowed too: the committed disable must not surface as a 500")
    void failingQueueWriteDoesNotEscapeTheAfterCommitCallback() {
        doThrow(new IllegalStateException("outbox unavailable"))
                .when(assignmentEnder)
                .apply(any(), any(), any(), any());
        when(retryRepository.save(any(EmployeeOffboardingRetry.class))).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> listener.onEmployeeOffboarded(event(AssignmentTerminationPolicy.IMMEDIATE, null)))
                .doesNotThrowAnyException();

        verify(retryRepository).save(any(EmployeeOffboardingRetry.class));
        // Both transactions rolled back; nothing was committed.
        verify(transactionManager, Mockito.times(2)).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    @Test
    @DisplayName("an IMMEDIATE failure is queued with no end date")
    void immediateFailureQueuesWithoutADate() {
        doThrow(new IllegalStateException("db down")).when(assignmentEnder).apply(any(), any(), any(), any());
        when(retryRepository.save(any(EmployeeOffboardingRetry.class))).thenAnswer(i -> i.getArgument(0));

        listener.onEmployeeOffboarded(event(AssignmentTerminationPolicy.IMMEDIATE, null));

        ArgumentCaptor<EmployeeOffboardingRetry> retry = ArgumentCaptor.forClass(EmployeeOffboardingRetry.class);
        verify(retryRepository).save(retry.capture());
        assertThat(retry.getValue().getAssignmentPolicy()).isEqualTo(AssignmentTerminationPolicy.IMMEDIATE);
        assertThat(retry.getValue().getAssignmentEndDate()).isNull();
    }
}

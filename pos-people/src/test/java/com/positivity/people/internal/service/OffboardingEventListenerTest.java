package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.entity.EmployeeOffboardingRetry;
import com.positivity.people.internal.enums.AssignmentTerminationPolicy;
import com.positivity.people.internal.event.EmployeeOffboardedEvent;
import com.positivity.people.internal.repository.EmployeeOffboardingRetryRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
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

/** After-commit offboarding handler (#2121, #2360). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OffboardingEventListener")
class OffboardingEventListenerTest {

    private static final Instant DUE = Instant.parse("2026-03-01T12:05:00Z");
    private static final UUID PERSON_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01");
    private static final UUID RETRY_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4c01");
    private static final EmployeeOffboardedEvent EVENT = new EmployeeOffboardedEvent(PERSON_ID, RETRY_ID);

    @Mock
    private OffboardingAssignmentEnder assignmentEnder;

    @Mock
    private EmployeeOffboardingRetryRepository retryRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    private OffboardingEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new OffboardingEventListener(assignmentEnder, retryRepository, transactionManager);
    }

    /** The row {@code disableEmployee} committed with the disable. */
    private EmployeeOffboardingRetry queuedRow(AssignmentTerminationPolicy policy, LocalDate endDate) {
        EmployeeOffboardingRetry row = new EmployeeOffboardingRetry();
        row.setId(RETRY_ID);
        row.setEmployeeId(PERSON_ID);
        row.setAssignmentPolicy(policy);
        row.setAssignmentEndDate(endDate);
        row.setDisableReason("Voluntary resignation");
        row.setActorId("hr.admin");
        row.setFailureReason(EmployeeOffboardingRetryWorker.NOT_YET_APPLIED);
        row.setAttempts(0);
        row.setNextAttemptAt(DUE);
        when(retryRepository.findByIdForUpdate(RETRY_ID)).thenReturn(Optional.of(row));
        return row;
    }

    @Test
    @DisplayName("applies the queued row's policy and deletes the row in one REQUIRES_NEW transaction")
    void appliesThePolicyAndDeletesTheRow() {
        LocalDate endDate = LocalDate.of(2026, 3, 31);
        EmployeeOffboardingRetry row = queuedRow(AssignmentTerminationPolicy.GRACE_PERIOD, endDate);

        listener.onEmployeeOffboarded(EVENT);

        // One transaction around both, so the row goes exactly when the policy is applied.
        InOrder order = Mockito.inOrder(transactionManager, assignmentEnder, retryRepository);
        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        order.verify(transactionManager).getTransaction(definition.capture());
        order.verify(assignmentEnder).apply(PERSON_ID, AssignmentTerminationPolicy.GRACE_PERIOD, endDate, "hr.admin");
        order.verify(retryRepository).delete(row);
        order.verify(transactionManager).commit(any());
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        verify(transactionManager, times(1)).getTransaction(any());
        verify(retryRepository, never()).save(any());
    }

    @Test
    @DisplayName("a failure is swallowed and leaves the row as it was: no second transaction, nothing written")
    void failureLeavesTheRowForTheWorker() {
        EmployeeOffboardingRetry row = queuedRow(AssignmentTerminationPolicy.GRACE_PERIOD, LocalDate.of(2026, 3, 31));
        doThrow(new IllegalStateException("outbox unavailable"))
                .when(assignmentEnder)
                .apply(any(), any(), any(), any());

        assertThatCode(() -> listener.onEmployeeOffboarded(EVENT)).doesNotThrowAnyException();

        // The only transaction rolled back; the row's durability never depended on this handler.
        verify(transactionManager, times(1)).getTransaction(any());
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
        verify(retryRepository, never()).delete(any(EmployeeOffboardingRetry.class));
        verify(retryRepository, never()).save(any());
        // Not counted as an attempt, and still due when disableEmployee said.
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getNextAttemptAt()).isEqualTo(DUE);
        assertThat(row.getFailureReason()).isEqualTo(EmployeeOffboardingRetryWorker.NOT_YET_APPLIED);
    }

    @Test
    @DisplayName("a failing commit is swallowed too: the committed disable must not surface as a 500")
    void failingCommitDoesNotEscapeTheAfterCommitCallback() {
        queuedRow(AssignmentTerminationPolicy.IMMEDIATE, null);
        doThrow(new IllegalStateException("db down")).when(transactionManager).commit(any());

        assertThatCode(() -> listener.onEmployeeOffboarded(EVENT)).doesNotThrowAnyException();

        verify(transactionManager, times(1)).getTransaction(any());
    }

    @Test
    @DisplayName("a row the worker has already settled is skipped")
    void rowAlreadySettledIsSkipped() {
        when(retryRepository.findByIdForUpdate(RETRY_ID)).thenReturn(Optional.empty());

        listener.onEmployeeOffboarded(EVENT);

        verify(assignmentEnder, never()).apply(any(), any(), any(), any());
        verify(retryRepository, never()).delete(any(EmployeeOffboardingRetry.class));
    }

    @Test
    @DisplayName("an IMMEDIATE row is applied with no end date")
    void immediateRowIsAppliedWithoutADate() {
        EmployeeOffboardingRetry row = queuedRow(AssignmentTerminationPolicy.IMMEDIATE, null);

        listener.onEmployeeOffboarded(EVENT);

        verify(assignmentEnder).apply(PERSON_ID, AssignmentTerminationPolicy.IMMEDIATE, null, "hr.admin");
        verify(retryRepository).delete(row);
    }
}

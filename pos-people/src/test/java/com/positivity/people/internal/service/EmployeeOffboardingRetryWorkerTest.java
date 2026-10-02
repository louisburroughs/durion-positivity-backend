package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.entity.Employee;
import com.positivity.people.internal.entity.EmployeeOffboardingRetry;
import com.positivity.people.internal.enums.AssignmentTerminationPolicy;
import com.positivity.people.internal.enums.EmployeeStatus;
import com.positivity.people.internal.repository.EmployeeOffboardingRetryRepository;
import com.positivity.people.internal.repository.EmployeeRepository;
import com.positivity.tenancy.StaticTenantRegistry;
import com.positivity.tenancy.TenancyProperties;
import com.positivity.tenancy.TenantIterator;
import com.positivity.tenancy.testing.TenantTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

/** Offboarding retry queue worker (#2121). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EmployeeOffboardingRetryWorker")
class EmployeeOffboardingRetryWorkerTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID PERSON_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01");
    private static final int MAX_ATTEMPTS = 3;

    @Mock
    private EmployeeOffboardingRetryRepository retryRepository;

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private OffboardingAssignmentEnder assignmentEnder;

    @Mock
    private PlatformTransactionManager transactionManager;

    private SimpleMeterRegistry meterRegistry;
    private EmployeeOffboardingRetryWorker worker;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        worker = newWorker(meterRegistry);
        when(employeeRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(employee(EmployeeStatus.DISABLED)));
    }

    @SuppressWarnings("unchecked")
    private EmployeeOffboardingRetryWorker newWorker(MeterRegistry registry) {
        TenancyProperties tenancy = new TenancyProperties();
        tenancy.setDefaultTenantId(TenantTestSupport.TENANT_A);
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return new EmployeeOffboardingRetryWorker(
                retryRepository,
                employeeRepository,
                assignmentEnder,
                new TenantIterator(new StaticTenantRegistry(tenancy)),
                CLOCK,
                transactionManager,
                MAX_ATTEMPTS,
                provider);
    }

    private static Employee employee(EmployeeStatus status) {
        return Employee.builder().personId(PERSON_ID).status(status).build();
    }

    private EmployeeOffboardingRetry row(int attempts, Instant nextAttemptAt) {
        EmployeeOffboardingRetry row = new EmployeeOffboardingRetry();
        row.setId(UUID.randomUUID());
        row.setEmployeeId(PERSON_ID);
        row.setAssignmentPolicy(AssignmentTerminationPolicy.GRACE_PERIOD);
        row.setAssignmentEndDate(LocalDate.of(2026, 3, 31));
        row.setActorId("hr.admin");
        row.setFailureReason("first failure");
        row.setAttempts(attempts);
        row.setNextAttemptAt(nextAttemptAt);
        return row;
    }

    private EmployeeOffboardingRetry dueRow(int attempts) {
        EmployeeOffboardingRetry row = row(attempts, NOW.minusSeconds(1));
        when(retryRepository.findByNextAttemptAtLessThanEqualAndAttemptsLessThanOrderByNextAttemptAtAsc(
                        NOW, MAX_ATTEMPTS))
                .thenReturn(List.of(row));
        when(retryRepository.findByIdForUpdate(row.getId())).thenReturn(Optional.of(row));
        return row;
    }

    @Test
    @DisplayName("a due row is re-applied with its own policy, end date and actor, then deleted")
    void dueRowSucceedsAndIsDeleted() {
        EmployeeOffboardingRetry row = dueRow(0);

        worker.sweepTenant();

        verify(assignmentEnder)
                .apply(PERSON_ID, AssignmentTerminationPolicy.GRACE_PERIOD, LocalDate.of(2026, 3, 31), "hr.admin");
        verify(retryRepository).delete(row);
        verify(retryRepository, never()).save(any());
    }

    @Test
    @DisplayName("a failing row counts the attempt, records the reason and backs off exponentially")
    void failingRowBacksOff() {
        EmployeeOffboardingRetry row = dueRow(1);
        doThrow(new IllegalStateException("outbox unavailable"))
                .when(assignmentEnder)
                .apply(any(), any(), any(), any());

        worker.sweepTenant();

        verify(retryRepository, never()).delete(any(EmployeeOffboardingRetry.class));
        verify(retryRepository).save(row);
        assertThat(row.getAttempts()).isEqualTo(2);
        assertThat(row.getFailureReason()).isEqualTo("outbox unavailable");
        assertThat(row.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(300 * 4));
    }

    @Test
    @DisplayName("one failing row does not stop the sweep: later rows and the expiry pass still run")
    void aFailureDoesNotStopTheSweep() {
        EmployeeOffboardingRetry failing = row(0, NOW.minusSeconds(10));
        EmployeeOffboardingRetry healthy = row(0, NOW.minusSeconds(5));
        healthy.setActorId("someone.else");
        when(retryRepository.findByNextAttemptAtLessThanEqualAndAttemptsLessThanOrderByNextAttemptAtAsc(
                        NOW, MAX_ATTEMPTS))
                .thenReturn(List.of(failing, healthy));
        when(retryRepository.findByIdForUpdate(failing.getId())).thenReturn(Optional.of(failing));
        when(retryRepository.findByIdForUpdate(healthy.getId())).thenReturn(Optional.of(healthy));
        doThrow(new IllegalStateException("boom")).when(assignmentEnder).apply(any(), any(), any(), eq("hr.admin"));

        worker.sweepTenant();

        verify(retryRepository).delete(healthy);
        verify(retryRepository, never()).delete(failing);
        assertThat(failing.getAttempts()).isEqualTo(1);
        verify(assignmentEnder).findLingeringAssignmentIds(MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("the attempt that reaches the maximum is recorded and the row is left for an operator")
    void exhaustedRowIsKeptAndNotRetriedAgain() {
        EmployeeOffboardingRetry row = dueRow(MAX_ATTEMPTS - 1);
        doThrow(new IllegalStateException("still down")).when(assignmentEnder).apply(any(), any(), any(), any());

        worker.sweepTenant();

        verify(retryRepository).save(row);
        verify(retryRepository, never()).delete(any(EmployeeOffboardingRetry.class));
        assertThat(row.getAttempts()).isEqualTo(MAX_ATTEMPTS);
        assertThat(row.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(300L << MAX_ATTEMPTS));
    }

    @Test
    @DisplayName("each scheduled pass publishes the number of exhausted rows across tenants as a gauge")
    void exhaustedRowsAreGauged() {
        when(retryRepository.findByNextAttemptAtLessThanEqualAndAttemptsLessThanOrderByNextAttemptAtAsc(
                        any(), anyInt()))
                .thenReturn(List.of());
        when(retryRepository.countByAttemptsGreaterThanEqual(MAX_ATTEMPTS)).thenReturn(2L);

        worker.runScheduledSweep();

        assertThat(meterRegistry
                        .get(EmployeeOffboardingRetryWorker.EXHAUSTED_GAUGE)
                        .gauge()
                        .value())
                .isEqualTo(2.0);

        when(retryRepository.countByAttemptsGreaterThanEqual(MAX_ATTEMPTS)).thenReturn(0L);
        worker.runScheduledSweep();
        assertThat(meterRegistry
                        .get(EmployeeOffboardingRetryWorker.EXHAUSTED_GAUGE)
                        .gauge()
                        .value())
                .isZero();
    }

    @Test
    @DisplayName("without a MeterRegistry the worker still runs")
    void runsWithoutAMeterRegistry() {
        when(retryRepository.findByNextAttemptAtLessThanEqualAndAttemptsLessThanOrderByNextAttemptAtAsc(
                        any(), anyInt()))
                .thenReturn(List.of());

        newWorker(null).runScheduledSweep();

        verify(assignmentEnder).findLingeringAssignmentIds(MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("a row already at the maximum is skipped even if it is handed to the worker")
    void exhaustedRowIsSkippedDefensively() {
        EmployeeOffboardingRetry row = dueRow(MAX_ATTEMPTS);

        worker.sweepTenant();

        verify(assignmentEnder, never()).apply(any(), any(), any(), any());
        verify(retryRepository, never()).delete(any(EmployeeOffboardingRetry.class));
        verify(retryRepository, never()).save(row);
    }

    @Test
    @DisplayName("rows are looked up as of now, so a not-yet-due row is never worked")
    void notYetDueRowsAreNotSelected() {
        EmployeeOffboardingRetry notDue = row(0, NOW.plusSeconds(60));
        when(retryRepository.findByIdForUpdate(notDue.getId())).thenReturn(Optional.of(notDue));
        when(retryRepository.findByNextAttemptAtLessThanEqualAndAttemptsLessThanOrderByNextAttemptAtAsc(
                        any(), anyInt()))
                .thenReturn(List.of());

        worker.sweepTenant();

        verify(retryRepository)
                .findByNextAttemptAtLessThanEqualAndAttemptsLessThanOrderByNextAttemptAtAsc(NOW, MAX_ATTEMPTS);
        verify(assignmentEnder, never()).apply(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a row that stopped being due before its own transaction read it is skipped")
    void rowRescheduledByAnotherInstanceIsSkipped() {
        EmployeeOffboardingRetry stale = row(0, NOW.minusSeconds(1));
        when(retryRepository.findByNextAttemptAtLessThanEqualAndAttemptsLessThanOrderByNextAttemptAtAsc(
                        NOW, MAX_ATTEMPTS))
                .thenReturn(List.of(stale));
        EmployeeOffboardingRetry rescheduled = row(1, NOW.plusSeconds(600));
        when(retryRepository.findByIdForUpdate(stale.getId())).thenReturn(Optional.of(rescheduled));

        worker.sweepTenant();

        verify(assignmentEnder, never()).apply(any(), any(), any(), any());
        verify(retryRepository, never()).delete(any(EmployeeOffboardingRetry.class));
    }

    @Test
    @DisplayName("a row whose employee was re-enabled is dropped without ending any assignment")
    void reEnabledEmployeeRowIsDropped() {
        EmployeeOffboardingRetry row = dueRow(0);
        when(employeeRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(employee(EmployeeStatus.ACTIVE)));

        worker.sweepTenant();

        verify(assignmentEnder, never()).apply(any(), any(), any(), any());
        verify(retryRepository).delete(row);
    }

    @Test
    @DisplayName("every pass also ends the assignments an offboarding left open, each in its own transaction")
    void sweepEndsLingeringAssignmentsOneTransactionEach() {
        when(retryRepository.findByNextAttemptAtLessThanEqualAndAttemptsLessThanOrderByNextAttemptAtAsc(
                        any(), anyInt()))
                .thenReturn(List.of());
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(assignmentEnder.findLingeringAssignmentIds(MAX_ATTEMPTS)).thenReturn(List.of(first, second));
        when(assignmentEnder.endLingeringAssignment(any())).thenReturn(true);

        worker.runScheduledSweep();

        verify(assignmentEnder).endLingeringAssignment(first);
        verify(assignmentEnder).endLingeringAssignment(second);
        verify(transactionManager, times(2)).getTransaction(any());
        verify(transactionManager, times(2)).commit(any());
        verify(transactionManager, never()).rollback(any());
    }

    @Test
    @DisplayName("one lingering assignment that cannot be ended does not roll back the others")
    void aLingeringAssignmentFailureDoesNotStopTheOthers() {
        when(retryRepository.findByNextAttemptAtLessThanEqualAndAttemptsLessThanOrderByNextAttemptAtAsc(
                        any(), anyInt()))
                .thenReturn(List.of());
        UUID broken = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        when(assignmentEnder.findLingeringAssignmentIds(MAX_ATTEMPTS)).thenReturn(List.of(broken, healthy));
        when(assignmentEnder.endLingeringAssignment(broken)).thenThrow(new IllegalStateException("outbox full"));
        when(assignmentEnder.endLingeringAssignment(healthy)).thenReturn(true);

        worker.sweepTenant();

        verify(assignmentEnder).endLingeringAssignment(broken);
        verify(assignmentEnder).endLingeringAssignment(healthy);
        // The broken one rolled back its own transaction; the healthy one committed its own.
        InOrder order = Mockito.inOrder(transactionManager);
        order.verify(transactionManager).getTransaction(any());
        order.verify(transactionManager).rollback(any());
        order.verify(transactionManager).getTransaction(any());
        order.verify(transactionManager).commit(any());
    }

    @Test
    @DisplayName("a failing candidate read is swallowed so the next scheduled run still happens")
    void lingeringReadFailureIsSwallowed() {
        when(retryRepository.findByNextAttemptAtLessThanEqualAndAttemptsLessThanOrderByNextAttemptAtAsc(
                        any(), anyInt()))
                .thenReturn(List.of());
        when(assignmentEnder.findLingeringAssignmentIds(MAX_ATTEMPTS)).thenThrow(new IllegalStateException("db down"));

        worker.runScheduledSweep();

        verify(assignmentEnder).findLingeringAssignmentIds(MAX_ATTEMPTS);
        verify(assignmentEnder, never()).endLingeringAssignment(any());
    }

    @Test
    @DisplayName("backoff doubles from five minutes and is capped at one day")
    void backoffIsExponentialAndCapped() {
        assertThat(EmployeeOffboardingRetryWorker.backoffSeconds(1)).isEqualTo(600);
        assertThat(EmployeeOffboardingRetryWorker.backoffSeconds(2)).isEqualTo(1200);
        assertThat(EmployeeOffboardingRetryWorker.backoffSeconds(8)).isEqualTo(76_800);
        assertThat(EmployeeOffboardingRetryWorker.backoffSeconds(9)).isEqualTo(86_400);
        assertThat(EmployeeOffboardingRetryWorker.backoffSeconds(1_000)).isEqualTo(86_400);
    }

    @Test
    @DisplayName("failure reasons fit the 255-character column and are never blank")
    void failureReasonIsBounded() {
        assertThat(EmployeeOffboardingRetryWorker.failureReason(null)).isEqualTo("unknown");
        assertThat(EmployeeOffboardingRetryWorker.failureReason(" ")).isEqualTo("unknown");
        assertThat(EmployeeOffboardingRetryWorker.failureReason("x".repeat(400)))
                .hasSize(255);
    }
}

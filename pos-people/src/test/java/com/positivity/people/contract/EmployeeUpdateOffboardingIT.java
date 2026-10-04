package com.positivity.people.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.people.BaseContractIntegrationTest;
import com.positivity.people.internal.config.PeopleEventPublisher;
import com.positivity.people.internal.entity.Employee;
import com.positivity.people.internal.entity.EmployeeLocationAssignment;
import com.positivity.people.internal.entity.EmployeeOffboardingRetry;
import com.positivity.people.internal.enums.AssignmentStatus;
import com.positivity.people.internal.enums.AssignmentTerminationPolicy;
import com.positivity.people.internal.enums.EmployeeStatus;
import com.positivity.people.internal.repository.EmployeeLocationAssignmentRepository;
import com.positivity.people.internal.repository.EmployeeOffboardingRetryRepository;
import com.positivity.people.internal.repository.EmployeeRepository;
import com.positivity.people.internal.service.EmployeeOffboardingRetryWorker;
import com.positivity.people.internal.service.OffboardingAssignmentEnder;
import com.positivity.people.internal.service.OffboardingEventListener;
import com.positivity.tenancy.TenantIterator;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * #2361: a status moved into TERMINATED or DISABLED through {@code updateEmployee} is an IMMEDIATE
 * offboarding. It writes the same queue row and raises the same after-commit event as
 * {@code disableEmployee}, so the employee's assignments, dated ones included, are ended by the
 * handler at once rather than the open-ended ones alone by the worker's sweep minutes later.
 */
@DisplayName("Employee offboarding through updateEmployee IT (#2361)")
class EmployeeUpdateOffboardingIT extends BaseContractIntegrationTest {

    private static final int MAX_ATTEMPTS = 10;

    /** Past a new row's first due time, which is five minutes after the status change. */
    private static final Duration PAST_FIRST_DELAY = Duration.ofMinutes(6);

    /** Where a test moves an employee's {@code statusEffectiveAt} back to, to see it restamped. */
    private static final Instant LONG_AGO = Instant.parse("2026-02-01T00:00:00Z");

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private EmployeeLocationAssignmentRepository assignmentRepository;

    @Autowired
    private EmployeeOffboardingRetryRepository retryRepository;

    @MockitoSpyBean
    private OffboardingAssignmentEnder assignmentEnder;

    @Autowired
    private TenantIterator tenantIterator;

    @Autowired
    private Clock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectProvider<MeterRegistry> meterRegistry;

    @MockitoSpyBean
    private PeopleEventPublisher peopleEventPublisher;

    @MockitoSpyBean
    private OffboardingEventListener offboardingEventListener;

    @Test
    @DisplayName("TERMINATED through updateEmployee: an assignment dated a year out is ended today and published")
    void terminationEndsADatedAssignment() throws Exception {
        UUID employeeId = createEmployee("EMP-2361-001", "employee.2361.001@example.com");
        UUID assignmentId = createAssignment(employeeId, today().plusYears(1));

        update(employeeId, "EMP-2361-001", "employee.2361.001@example.com", EmployeeStatus.TERMINATED);

        EmployeeLocationAssignment ended =
                assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(ended.getStatus()).isEqualTo(AssignmentStatus.ENDED);
        assertThat(ended.getEffectiveTo()).isEqualTo(today());
        // The people.staffing-assignment.updated fact (StaffingAssignmentUpdatedV1) for the ended row.
        verify(peopleEventPublisher)
                .publishStaffingAssignmentUpdated(
                        argThat(published -> published.getId().equals(assignmentId)
                                && published.getStatus() == AssignmentStatus.ENDED
                                && today().equals(published.getEffectiveTo())));
        assertThat(retryRepository.count()).isZero();
    }

    @Test
    @DisplayName("TERMINATED through updateEmployee: an open-ended assignment is ended by the handler at once,"
            + " statusEffectiveAt is restamped and no queue row is left")
    void terminationEndsAnOpenEndedAssignmentAtOnce() throws Exception {
        UUID employeeId = createEmployee("EMP-2361-002", "employee.2361.002@example.com");
        UUID assignmentId = createAssignment(employeeId, null);
        backdateStatus(employeeId);

        update(employeeId, "EMP-2361-002", "employee.2361.002@example.com", EmployeeStatus.TERMINATED);

        // No worker pass has run (the test profile turns the scheduled bean off): this is the
        // after-commit handler, with the actor of the request.
        EmployeeLocationAssignment ended =
                assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(ended.getStatus()).isEqualTo(AssignmentStatus.ENDED);
        assertThat(ended.getEffectiveTo()).isEqualTo(today());
        verify(assignmentEnder).apply(employeeId, AssignmentTerminationPolicy.IMMEDIATE, null, TEST_USER);
        assertThat(retryRepository.count()).isZero();
        Employee terminated = employeeRepository.findByPersonId(employeeId).orElseThrow();
        assertThat(terminated.getStatus()).isEqualTo(EmployeeStatus.TERMINATED);
        assertThat(terminated.getStatusEffectiveAt()).isAfter(LONG_AGO);
    }

    @Test
    @DisplayName("DISABLED through updateEmployee is the same offboarding: the assignment is ended")
    void disablingThroughUpdateEndsTheAssignment() throws Exception {
        UUID employeeId = createEmployee("EMP-2361-003", "employee.2361.003@example.com");
        UUID assignmentId = createAssignment(employeeId, today().plusYears(1));

        update(employeeId, "EMP-2361-003", "employee.2361.003@example.com", EmployeeStatus.DISABLED);

        EmployeeLocationAssignment ended =
                assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(ended.getStatus()).isEqualTo(AssignmentStatus.ENDED);
        assertThat(ended.getEffectiveTo()).isEqualTo(today());
        assertThat(retryRepository.count()).isZero();
    }

    @Test
    @DisplayName("Handler fails: the termination still answers 200, exactly one IMMEDIATE row is left, and the"
            + " worker ends the dated assignment from it")
    void aFailedHandlerLeavesOneImmediateRowForTheWorker() throws Exception {
        UUID employeeId = createEmployee("EMP-2361-004", "employee.2361.004@example.com");
        LocalDate aYearOut = today().plusYears(1);
        UUID assignmentId = createAssignment(employeeId, aYearOut);
        doThrow(new IllegalStateException("outbox unavailable"))
                .when(peopleEventPublisher)
                .publishStaffingAssignmentUpdated(any());

        update(employeeId, "EMP-2361-004", "employee.2361.004@example.com", EmployeeStatus.TERMINATED);

        assertThat(employeeRepository.findByPersonId(employeeId).orElseThrow().getStatus())
                .isEqualTo(EmployeeStatus.TERMINATED);
        assertActiveUntil(assignmentId, aYearOut);
        EmployeeOffboardingRetry row = onlyQueueRow();
        assertThat(row.getEmployeeId()).isEqualTo(employeeId);
        assertThat(row.getAssignmentPolicy()).isEqualTo(AssignmentTerminationPolicy.IMMEDIATE);
        assertThat(row.getAssignmentEndDate()).isNull();
        assertThat(row.getDisableReason()).isEqualTo("Status set to TERMINATED through updateEmployee");
        assertThat(row.getActorId()).isEqualTo(TEST_USER);
        assertThat(row.getFailureReason()).isEqualTo("not yet applied by the after-commit handler");
        assertThat(row.getAttempts()).isZero();

        // The sweep alone would never end this one: it is dated, not open-ended and not yet past.
        reset(peopleEventPublisher);
        workerAt(clock).runScheduledSweep();
        assertActiveUntil(assignmentId, aYearOut);
        assertThat(onlyQueueRow().getAttempts()).isZero();

        workerAt(Clock.offset(clock, PAST_FIRST_DELAY)).runScheduledSweep();

        EmployeeLocationAssignment ended =
                assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(ended.getStatus()).isEqualTo(AssignmentStatus.ENDED);
        assertThat(ended.getEffectiveTo()).isEqualTo(today());
        assertThat(retryRepository.count()).isZero();
    }

    @Test
    @DisplayName("Status unchanged: an update of another field queues nothing and ends nothing")
    void anUpdateThatKeepsTheStatusIsNotAnOffboarding() throws Exception {
        UUID employeeId = createEmployee("EMP-2361-005", "employee.2361.005@example.com");
        UUID assignmentId = createAssignment(employeeId, null);
        backdateStatus(employeeId);

        update(employeeId, "EMP-2361-005-B", "employee.2361.005@example.com", EmployeeStatus.ACTIVE);

        verify(offboardingEventListener, never()).onEmployeeOffboarded(any());
        assertThat(retryRepository.count()).isZero();
        assertActiveUntil(assignmentId, null);
        Employee updated = employeeRepository.findByPersonId(employeeId).orElseThrow();
        assertThat(updated.getEmployeeNumber()).isEqualTo("EMP-2361-005-B");
        assertThat(updated.getStatusEffectiveAt()).isEqualTo(LONG_AGO);
    }

    @Test
    @DisplayName("Already TERMINATED: an update of another field is not a second offboarding")
    void anUpdateOfAnAlreadyTerminatedEmployeeQueuesNothing() throws Exception {
        UUID employeeId = createEmployee("EMP-2361-006", "employee.2361.006@example.com");
        update(employeeId, "EMP-2361-006", "employee.2361.006@example.com", EmployeeStatus.TERMINATED);
        backdateStatus(employeeId);
        // Not something the API can produce for a terminated employee; it stands for whatever a
        // second offboarding would wrongly touch.
        LocalDate aYearOut = today().plusYears(1);
        UUID assignmentId = createAssignment(employeeId, aYearOut);
        reset(offboardingEventListener, assignmentEnder);

        update(employeeId, "EMP-2361-006-B", "employee.2361.006@example.com", EmployeeStatus.TERMINATED);

        verify(offboardingEventListener, never()).onEmployeeOffboarded(any());
        verify(assignmentEnder, never()).apply(any(), any(), any(), any());
        assertThat(retryRepository.count()).isZero();
        assertActiveUntil(assignmentId, aYearOut);
        Employee updated = employeeRepository.findByPersonId(employeeId).orElseThrow();
        assertThat(updated.getEmployeeNumber()).isEqualTo("EMP-2361-006-B");
        assertThat(updated.getStatusEffectiveAt()).isEqualTo(LONG_AGO);
    }

    @Test
    @DisplayName("DISABLED with a grace period, then TERMINATED through updateEmployee: the termination is a new"
            + " IMMEDIATE offboarding that cuts the grace period short (#2418)")
    void terminatingAnAlreadyDisabledEmployeeCutsTheGracePeriodShort() throws Exception {
        UUID employeeId = createEmployee("EMP-2361-007", "employee.2361.007@example.com");
        UUID assignmentId = createAssignment(employeeId, null);
        LocalDate graceEnd = today().plusDays(14);
        mockMvc.perform(withAuth(post("/v1/people/employees/{employeeId}/disable", employeeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignmentPolicy\": \"GRACE_PERIOD\", \"assignmentEndDate\": \"%s\"}"
                                .formatted(graceEnd))))
                .andExpect(status().isOk());
        assertActiveUntil(assignmentId, graceEnd);

        update(employeeId, "EMP-2361-007", "employee.2361.007@example.com", EmployeeStatus.TERMINATED);

        // Once for the disable, once for the termination.
        verify(offboardingEventListener, times(2)).onEmployeeOffboarded(any());
        verify(assignmentEnder).apply(employeeId, AssignmentTerminationPolicy.IMMEDIATE, null, TEST_USER);
        assertThat(retryRepository.count()).isZero();
        EmployeeLocationAssignment ended =
                assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(ended.getStatus()).isEqualTo(AssignmentStatus.ENDED);
        assertThat(ended.getEffectiveTo()).isEqualTo(today());
    }

    @Test
    @DisplayName("Disable, re-enable, disable with the handler never running: only the newest policy's row is"
            + " left (#2418)")
    void aNewOffboardingSupersedesTheEarlierPendingRow() throws Exception {
        UUID employeeId = createEmployee("EMP-2418-001", "employee.2418.001@example.com");
        createAssignment(employeeId, null);
        doNothing().when(offboardingEventListener).onEmployeeOffboarded(any());
        mockMvc.perform(withAuth(post("/v1/people/employees/{employeeId}/disable", employeeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignmentPolicy\": \"IMMEDIATE\"}")))
                .andExpect(status().isOk());
        update(employeeId, "EMP-2418-001", "employee.2418.001@example.com", EmployeeStatus.ACTIVE);
        assertThat(retryRepository.count()).isEqualTo(1);
        LocalDate graceEnd = today().plusDays(14);
        mockMvc.perform(withAuth(post("/v1/people/employees/{employeeId}/disable", employeeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignmentPolicy\": \"GRACE_PERIOD\", \"assignmentEndDate\": \"%s\"}"
                                .formatted(graceEnd))))
                .andExpect(status().isOk());

        EmployeeOffboardingRetry row = onlyQueueRow();
        assertThat(row.getAssignmentPolicy()).isEqualTo(AssignmentTerminationPolicy.GRACE_PERIOD);
        assertThat(row.getAssignmentEndDate()).isEqualTo(graceEnd);
    }

    @Test
    @DisplayName("Terminated, handler never ran, then reactivated through updateEmployee: the worker drops the"
            + " row and ends nothing")
    void aReactivationBeforeTheWorkerRunsKeepsTheAssignment() throws Exception {
        UUID employeeId = createEmployee("EMP-2361-008", "employee.2361.008@example.com");
        UUID assignmentId = createAssignment(employeeId, null);
        doNothing().when(offboardingEventListener).onEmployeeOffboarded(any());
        update(employeeId, "EMP-2361-008", "employee.2361.008@example.com", EmployeeStatus.TERMINATED);
        assertThat(onlyQueueRow().getAssignmentPolicy()).isEqualTo(AssignmentTerminationPolicy.IMMEDIATE);

        update(employeeId, "EMP-2361-008", "employee.2361.008@example.com", EmployeeStatus.ACTIVE);
        // The reactivation is no offboarding: still the one row.
        assertThat(retryRepository.count()).isEqualTo(1);

        workerAt(Clock.offset(clock, PAST_FIRST_DELAY)).runScheduledSweep();

        assertThat(retryRepository.count()).isZero();
        assertActiveUntil(assignmentId, null);
    }

    /**
     * The retry worker as production wires it, on the given clock. The test profile turns the
     * scheduled bean off, and a worker built here can be moved past a row's due time without
     * touching the row.
     */
    private EmployeeOffboardingRetryWorker workerAt(Clock workerClock) {
        return new EmployeeOffboardingRetryWorker(
                retryRepository,
                employeeRepository,
                assignmentEnder,
                tenantIterator,
                workerClock,
                transactionManager,
                MAX_ATTEMPTS,
                meterRegistry);
    }

    /** The full-replacement PUT, with the identity the employee was created with. */
    private void update(UUID employeeId, String employeeNumber, String email, EmployeeStatus status) throws Exception {
        mockMvc.perform(withAuth(put("/v1/people/employees/{employeeId}", employeeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(employeePayload(employeeNumber, email, status))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(status.name()));
    }

    private void backdateStatus(UUID employeeId) {
        Employee employee = employeeRepository.findByPersonId(employeeId).orElseThrow();
        employee.setStatusEffectiveAt(LONG_AGO);
        employeeRepository.saveAndFlush(employee);
    }

    private void assertActiveUntil(UUID assignmentId, LocalDate effectiveTo) {
        EmployeeLocationAssignment assignment =
                assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        assertThat(assignment.getEffectiveTo()).isEqualTo(effectiveTo);
    }

    private EmployeeOffboardingRetry onlyQueueRow() {
        List<EmployeeOffboardingRetry> rows = retryRepository.findAll();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    /** The service and the worker date from the UTC {@code Clock} bean, so compare in UTC too. */
    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    private UUID createAssignment(UUID personId, LocalDate effectiveTo) {
        Employee employee = employeeRepository.findByPersonId(personId).orElseThrow();
        return assignmentRepository
                .saveAndFlush(EmployeeLocationAssignment.builder()
                        .employee(employee)
                        .locationId(UUID.randomUUID())
                        .role("TECHNICIAN")
                        .effectiveFrom(today().minusDays(30))
                        .effectiveTo(effectiveTo)
                        .status(AssignmentStatus.ACTIVE)
                        .build())
                .getId();
    }

    private UUID createEmployee(String employeeNumber, String email) throws Exception {
        String response = mockMvc.perform(withAuth(post("/v1/people/employees")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(employeePayload(employeeNumber, email, EmployeeStatus.ACTIVE))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private static String employeePayload(String employeeNumber, String email, EmployeeStatus status) {
        String digits = email.replaceAll("\\D", "");
        String numericSuffix = digits.substring(digits.length() - 4);
        return """
                {
                  "firstName": "Offboarding",
                  "lastName": "Employee",
                  "preferredName": "Offboarding",
                  "employeeNumber": "%s",
                  "status": "%s",
                  "hireDate": "2026-02-01",
                  "duplicatePolicy": "STRICT",
                  "contactInfo": {
                    "primaryEmail": "%s",
                    "primaryPhone": "555-9%s-0110"
                  }
                }
                """.formatted(employeeNumber, status, email, numericSuffix);
    }
}

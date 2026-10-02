package com.positivity.people.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * #2360: the offboarding queue row is written with the disable, not by the after-commit handler on
 * its failure path, so a committed disable keeps its policy and end date whatever happens to the
 * handler, and the retry worker applies that policy rather than the sweep ending the assignments
 * today.
 */
@DisplayName("Employee offboarding durable queue row IT (#2360)")
class EmployeeOffboardingDurableQueueIT extends BaseContractIntegrationTest {

    private static final int MAX_ATTEMPTS = 10;

    /** Past a new row's first due time, which is five minutes after its disable. */
    private static final Duration PAST_FIRST_DELAY = Duration.ofMinutes(6);

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private EmployeeLocationAssignmentRepository assignmentRepository;

    @Autowired
    private EmployeeOffboardingRetryRepository retryRepository;

    @Autowired
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
    @DisplayName("GRACE_PERIOD, handler fails: 200, the row was durable before the handler ran, and the worker"
            + " dates the assignment to the grace end")
    void gracePeriodWhoseHandlerFailsIsDatedByTheWorker() throws Exception {
        UUID employeeId = createEmployee("EMP-2360-001", "employee.2360.001@example.com");
        UUID assignmentId = createAssignment(employeeId);
        LocalDate graceEnd = today().plusDays(14);
        // What the handler's own transaction can see of the queue at the moment it fails: only
        // committed rows, so a row seen here was committed by the disable, not by the handler.
        AtomicLong rowsDurableWhenTheHandlerRan = new AtomicLong(-1);
        doAnswer(invocation -> {
                    rowsDurableWhenTheHandlerRan.set(retryRepository.count());
                    throw new IllegalStateException("outbox unavailable");
                })
                .when(peopleEventPublisher)
                .publishStaffingAssignmentUpdated(any());

        disable(employeeId, AssignmentTerminationPolicy.GRACE_PERIOD, graceEnd);

        assertThat(rowsDurableWhenTheHandlerRan).hasValue(1);
        assertThat(employeeRepository.findByPersonId(employeeId).orElseThrow().getStatus())
                .isEqualTo(EmployeeStatus.DISABLED);
        assertOpenAndActive(assignmentId);
        EmployeeOffboardingRetry row = onlyQueueRow();
        assertThat(row.getEmployeeId()).isEqualTo(employeeId);
        assertThat(row.getAssignmentPolicy()).isEqualTo(AssignmentTerminationPolicy.GRACE_PERIOD);
        assertThat(row.getAssignmentEndDate()).isEqualTo(graceEnd);
        assertThat(row.getDisableReason()).isEqualTo("offboarding");
        assertThat(row.getActorId()).isEqualTo(TEST_USER);
        // The handler's failure is not an attempt: the worker's backoff and cap count from zero.
        assertThat(row.getAttempts()).isZero();

        // A worker pass that comes round before the row is due leaves it alone: it does not race
        // the handler, and here it would only have failed on the same publisher.
        workerAt(clock).runScheduledSweep();
        assertThat(onlyQueueRow().getAttempts()).isZero();
        assertOpenAndActive(assignmentId);

        reset(peopleEventPublisher);
        workerAt(Clock.offset(clock, PAST_FIRST_DELAY)).runScheduledSweep();

        EmployeeLocationAssignment dated =
                assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(dated.getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        assertThat(dated.getEffectiveTo()).isEqualTo(graceEnd);
        assertThat(retryRepository.count()).isZero();
    }

    @Test
    @DisplayName("GRACE_PERIOD, handler never runs (process died after the commit): the row is there and the"
            + " worker dates the assignment to the grace end, not today")
    void gracePeriodWhoseHandlerNeverRanIsDatedByTheWorker() throws Exception {
        UUID employeeId = createEmployee("EMP-2360-002", "employee.2360.002@example.com");
        UUID assignmentId = createAssignment(employeeId);
        LocalDate graceEnd = today().plusDays(14);
        doNothing().when(offboardingEventListener).onEmployeeOffboarded(any());

        disable(employeeId, AssignmentTerminationPolicy.GRACE_PERIOD, graceEnd);

        assertOpenAndActive(assignmentId);
        EmployeeOffboardingRetry row = onlyQueueRow();
        assertThat(row.getEmployeeId()).isEqualTo(employeeId);
        assertThat(row.getAssignmentPolicy()).isEqualTo(AssignmentTerminationPolicy.GRACE_PERIOD);
        assertThat(row.getAssignmentEndDate()).isEqualTo(graceEnd);
        assertThat(row.getAttempts()).isZero();

        workerAt(Clock.offset(clock, PAST_FIRST_DELAY)).runScheduledSweep();

        EmployeeLocationAssignment dated =
                assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(dated.getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        assertThat(dated.getEffectiveTo()).isEqualTo(graceEnd);
        assertThat(retryRepository.count()).isZero();
    }

    @Test
    @DisplayName("IMMEDIATE, handler never runs: the row carries IMMEDIATE and no date, and the worker ends the"
            + " assignment")
    void immediateWhoseHandlerNeverRanIsEndedByTheWorker() throws Exception {
        UUID employeeId = createEmployee("EMP-2360-003", "employee.2360.003@example.com");
        UUID assignmentId = createAssignment(employeeId);
        doNothing().when(offboardingEventListener).onEmployeeOffboarded(any());

        disable(employeeId, AssignmentTerminationPolicy.IMMEDIATE, null);

        assertOpenAndActive(assignmentId);
        EmployeeOffboardingRetry row = onlyQueueRow();
        assertThat(row.getAssignmentPolicy()).isEqualTo(AssignmentTerminationPolicy.IMMEDIATE);
        assertThat(row.getAssignmentEndDate()).isNull();

        workerAt(Clock.offset(clock, PAST_FIRST_DELAY)).runScheduledSweep();

        EmployeeLocationAssignment ended =
                assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(ended.getStatus()).isEqualTo(AssignmentStatus.ENDED);
        assertThat(ended.getEffectiveTo()).isEqualTo(today());
        assertThat(retryRepository.count()).isZero();
    }

    @Test
    @DisplayName("GRACE_PERIOD, handler succeeds: the assignment is dated and the row is gone")
    void gracePeriodHappyPathLeavesNoRow() throws Exception {
        UUID employeeId = createEmployee("EMP-2360-004", "employee.2360.004@example.com");
        UUID assignmentId = createAssignment(employeeId);
        LocalDate graceEnd = today().plusDays(14);
        // The row the handler is about to delete was committed by the disable.
        AtomicLong rowsDurableWhenTheHandlerRan = new AtomicLong(-1);
        doAnswer(invocation -> {
                    rowsDurableWhenTheHandlerRan.set(retryRepository.count());
                    return invocation.callRealMethod();
                })
                .when(peopleEventPublisher)
                .publishStaffingAssignmentUpdated(any());

        disable(employeeId, AssignmentTerminationPolicy.GRACE_PERIOD, graceEnd);

        assertThat(rowsDurableWhenTheHandlerRan).hasValue(1);
        EmployeeLocationAssignment dated =
                assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(dated.getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        assertThat(dated.getEffectiveTo()).isEqualTo(graceEnd);
        assertThat(retryRepository.count()).isZero();
    }

    @Test
    @DisplayName("IMMEDIATE, handler succeeds: the assignment is ended and the row is gone")
    void immediateHappyPathLeavesNoRow() throws Exception {
        UUID employeeId = createEmployee("EMP-2360-005", "employee.2360.005@example.com");
        UUID assignmentId = createAssignment(employeeId);

        disable(employeeId, AssignmentTerminationPolicy.IMMEDIATE, null);

        EmployeeLocationAssignment ended =
                assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(ended.getStatus()).isEqualTo(AssignmentStatus.ENDED);
        assertThat(ended.getEffectiveTo()).isEqualTo(today());
        assertThat(retryRepository.count()).isZero();
    }

    @Test
    @DisplayName("a disableReason longer than the queue column does not fail the disable")
    void overLongDisableReasonStillDisables() throws Exception {
        UUID employeeId = createEmployee("EMP-2360-006", "employee.2360.006@example.com");
        doNothing().when(offboardingEventListener).onEmployeeOffboarded(any());
        String payload = "{\"disableReason\": \"%s\", \"assignmentPolicy\": \"IMMEDIATE\"}".formatted("r".repeat(300));

        mockMvc.perform(withAuth(post("/v1/people/employees/{employeeId}/disable", employeeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        assertThat(onlyQueueRow().getDisableReason()).isEqualTo("r".repeat(255));
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

    private void disable(UUID employeeId, AssignmentTerminationPolicy policy, LocalDate assignmentEndDate)
            throws Exception {
        String endDate =
                assignmentEndDate == null ? "" : ", \"assignmentEndDate\": \"%s\"".formatted(assignmentEndDate);
        String payload =
                "{\"disableReason\": \"offboarding\", \"assignmentPolicy\": \"%s\"%s}".formatted(policy, endDate);

        mockMvc.perform(withAuth(post("/v1/people/employees/{employeeId}/disable", employeeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
    }

    private void assertOpenAndActive(UUID assignmentId) {
        EmployeeLocationAssignment assignment =
                assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        assertThat(assignment.getEffectiveTo()).isNull();
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

    private UUID createAssignment(UUID personId) {
        Employee employee = employeeRepository.findByPersonId(personId).orElseThrow();
        return assignmentRepository
                .saveAndFlush(EmployeeLocationAssignment.builder()
                        .employee(employee)
                        .locationId(UUID.randomUUID())
                        .role("TECHNICIAN")
                        .effectiveFrom(today().minusDays(30))
                        .status(AssignmentStatus.ACTIVE)
                        .build())
                .getId();
    }

    private UUID createEmployee(String employeeNumber, String email) throws Exception {
        String numericSuffix = employeeNumber.replaceAll("\\D", "");
        numericSuffix = numericSuffix.substring(numericSuffix.length() - 4);
        String payload = """
                {
                  "firstName": "Offboarding",
                  "lastName": "Employee",
                  "preferredName": "Offboarding",
                  "employeeNumber": "%s",
                  "status": "ACTIVE",
                  "hireDate": "2026-02-01",
                  "duplicatePolicy": "STRICT",
                  "contactInfo": {
                    "primaryEmail": "%s",
                    "primaryPhone": "555-8%s-0110"
                  }
                }
                """.formatted(employeeNumber, email, numericSuffix);

        String response = mockMvc.perform(withAuth(post("/v1/people/employees")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }
}

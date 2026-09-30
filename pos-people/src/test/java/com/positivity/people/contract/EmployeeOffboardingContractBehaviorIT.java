package com.positivity.people.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.people.BaseContractIntegrationTest;
import com.positivity.people.internal.entity.Employee;
import com.positivity.people.internal.entity.EmployeeLocationAssignment;
import com.positivity.people.internal.entity.EmployeeOffboardingRetry;
import com.positivity.people.internal.enums.AssignmentStatus;
import com.positivity.people.internal.enums.AssignmentTerminationPolicy;
import com.positivity.people.internal.enums.EmployeeStatus;
import com.positivity.people.internal.repository.EmployeeLocationAssignmentRepository;
import com.positivity.people.internal.repository.EmployeeOffboardingRetryRepository;
import com.positivity.people.internal.repository.EmployeeRepository;
import com.positivity.people.internal.service.OffboardingAssignmentEnder;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@DisplayName("Employee Offboarding ContractBehaviorIT")
class EmployeeOffboardingContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private EmployeeLocationAssignmentRepository assignmentRepository;

    @Autowired
    private EmployeeOffboardingRetryRepository retryRepository;

    @Autowired
    private OffboardingAssignmentEnder assignmentEnder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("CP-117-010: Disable active employee -> 200 OK, status=DISABLED")
    void cp117010_disableActiveEmployee_returns200() throws Exception {
        UUID employeeId = createEmployee("EMP-117-010", "employee.117.010@example.com");
        UUID assignmentId = createAssignment(employeeId);

        String payload = """
				{
				  "disableReason": "offboarding complete",
				                                                        "assignmentPolicy": "IMMEDIATE"
				}
				""";

        mockMvc.perform(withAuth(post("/v1/people/employees/{employeeId}/disable", employeeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        // #2121: offboarding ends the person's staffing assignment, it does not just log.
        EmployeeLocationAssignment ended =
                assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(ended.getStatus()).isEqualTo(AssignmentStatus.ENDED);
        assertThat(ended.getEffectiveTo()).isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("VE-117-010: Disable already-disabled employee -> 409 (stateful collision, ADR-0017 §2)")
    void ve117010_disableAlreadyDisabledEmployee_returns409() throws Exception {
        UUID employeeId = createEmployee("EMP-117-011", "employee.117.011@example.com");

        String payload = """
				{
				  "disableReason": "offboarding complete",
				                                                        "assignmentPolicy": "IMMEDIATE"
				}
				""";

        mockMvc.perform(withAuth(post("/v1/people/employees/{employeeId}/disable", employeeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isOk());

        mockMvc.perform(withAuth(post("/v1/people/employees/{employeeId}/disable", employeeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_STATE_CONFLICT"))
                .andExpect(jsonPath("$.message").value("Employee is already DISABLED or TERMINATED"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    @DisplayName("LC-117-010: Disable terminates assignments per policy")
    void lc117010_disableTerminatesAssignmentsPerPolicy_returns200() throws Exception {
        UUID employeeId = createEmployee("EMP-117-012", "employee.117.012@example.com");
        UUID assignmentId = createAssignment(employeeId);
        LocalDate graceEnd = LocalDate.now().plusDays(14);

        String payload = """
				{
				  "disableReason": "scheduled leave",
				                                                        "assignmentPolicy": "GRACE_PERIOD",
				                                                        "assignmentEndDate": "%s"
				}
				""".formatted(graceEnd);

        mockMvc.perform(withAuth(post("/v1/people/employees/{employeeId}/disable", employeeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        // #2121: the assignment keeps working until the grace date; the retry worker ends it after.
        EmployeeLocationAssignment dated =
                assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(dated.getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        assertThat(dated.getEffectiveTo()).isEqualTo(graceEnd);
    }

    @Test
    @DisplayName("VE-117-011: GRACE_PERIOD without a future-or-today assignmentEndDate is refused")
    void ve117011_gracePeriodNeedsAnEndDate() throws Exception {
        UUID employeeId = createEmployee("EMP-117-013", "employee.117.013@example.com");
        UUID assignmentId = createAssignment(employeeId);

        mockMvc.perform(withAuth(post("/v1/people/employees/{employeeId}/disable", employeeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignmentPolicy\": \"GRACE_PERIOD\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(withAuth(post("/v1/people/employees/{employeeId}/disable", employeeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignmentPolicy\": \"GRACE_PERIOD\", \"assignmentEndDate\": \"%s\"}"
                                .formatted(LocalDate.now().minusDays(1)))))
                .andExpect(status().isUnprocessableEntity());

        assertThat(assignmentRepository.findById(assignmentId).orElseThrow().getStatus())
                .isEqualTo(AssignmentStatus.ACTIVE);
        assertThat(employeeRepository.findByPersonId(employeeId).orElseThrow().getStatus())
                .isNotEqualTo(EmployeeStatus.DISABLED);
    }

    @Test
    @DisplayName("#2121: the worker's sweep ends open assignments an offboarding left behind, and only those")
    void sweepEndsOpenAssignmentsOfSettledOffboardedEmployees() throws Exception {
        // Process died after the disable committed: DISABLED an hour ago, assignment still open.
        UUID stranded = offboardedEmployee(
                "EMP-117-020", "employee.117.020@example.com", Instant.now().minusSeconds(3600));
        UUID strandedAssignment = createAssignment(stranded);
        // A retry is pending, so the queue (with its own policy) owns this one.
        UUID queued = offboardedEmployee(
                "EMP-117-021", "employee.117.021@example.com", Instant.now().minusSeconds(3600));
        UUID queuedAssignment = createAssignment(queued);
        EmployeeOffboardingRetry retry = new EmployeeOffboardingRetry();
        retry.setEmployeeId(queued);
        retry.setAssignmentPolicy(AssignmentTerminationPolicy.IMMEDIATE);
        retry.setActorId("system");
        retry.setFailureReason("boom");
        retry.setNextAttemptAt(Instant.now().plusSeconds(300));
        retryRepository.saveAndFlush(retry);
        // Disabled a moment ago: the after-commit handler has not necessarily run yet.
        UUID fresh = offboardedEmployee("EMP-117-022", "employee.117.022@example.com", Instant.now());
        UUID freshAssignment = createAssignment(fresh);

        int ended = new TransactionTemplate(transactionManager)
                .execute(status -> assignmentEnder.endLingeringAssignments());

        assertThat(ended).isEqualTo(1);
        EmployeeLocationAssignment swept =
                assignmentRepository.findById(strandedAssignment).orElseThrow();
        assertThat(swept.getStatus()).isEqualTo(AssignmentStatus.ENDED);
        assertThat(swept.getEffectiveTo()).isEqualTo(LocalDate.now());
        assertThat(assignmentRepository.findById(queuedAssignment).orElseThrow().getStatus())
                .isEqualTo(AssignmentStatus.ACTIVE);
        assertThat(assignmentRepository.findById(freshAssignment).orElseThrow().getStatus())
                .isEqualTo(AssignmentStatus.ACTIVE);
    }

    private UUID offboardedEmployee(String employeeNumber, String email, Instant statusEffectiveAt) throws Exception {
        UUID employeeId = createEmployee(employeeNumber, email);
        Employee employee = employeeRepository.findByPersonId(employeeId).orElseThrow();
        employee.setStatus(EmployeeStatus.DISABLED);
        employee.setStatusEffectiveAt(statusEffectiveAt);
        employeeRepository.saveAndFlush(employee);
        return employeeId;
    }

    private UUID createAssignment(UUID personId) {
        Employee employee = employeeRepository.findByPersonId(personId).orElseThrow();
        return assignmentRepository
                .saveAndFlush(EmployeeLocationAssignment.builder()
                        .employee(employee)
                        .locationId(UUID.randomUUID())
                        .role("TECHNICIAN")
                        .effectiveFrom(LocalDate.now().minusDays(30))
                        .status(AssignmentStatus.ACTIVE)
                        .build())
                .getId();
    }

    private UUID createEmployee(String employeeNumber, String email) throws Exception {
        String numericSuffix = employeeNumber.replaceAll("\\D", "");
        if (numericSuffix.length() > 4) {
            numericSuffix = numericSuffix.substring(numericSuffix.length() - 4);
        }
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

package com.positivity.people.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.people.BaseContractIntegrationTest;
import com.positivity.people.internal.entity.Employee;
import com.positivity.people.internal.entity.EmployeeLocationAssignment;
import com.positivity.people.internal.entity.ExtPersonReplica;
import com.positivity.people.internal.enums.EmployeeStatus;
import com.positivity.people.internal.repository.EmployeeLocationAssignmentRepository;
import com.positivity.people.internal.repository.EmployeeRepository;
import com.positivity.people.internal.repository.ExtPersonReplicaRepository;
import com.positivity.people.internal.repository.WorkSessionBreakRepository;
import com.positivity.people.internal.repository.WorkSessionRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Work-session contract. The integration caller is not linked to any person, so every mutation
 * here exercises the supervisory path of {@code WorkSessionAccessPolicy} (issue #2061 BR4, #85
 * OQ2): {@code people:timekeeping:approve} for start/stop/break/submit and
 * {@code people:timekeeping:view} for the reads, both unscoped for this token.
 */
@DisplayName("Work Session ContractIT")
class WorkSessionContractIT extends BaseContractIntegrationTest {

    /** The base authorities plus the supervisory timekeeping grants the work-session surface needs. */
    private static final String SUPERVISOR_AUTHORITIES =
            TEST_AUTHORITIES + ",people:timekeeping:approve,people:timekeeping:view";

    @Autowired
    private WorkSessionRepository workSessionRepository;

    @Autowired
    private WorkSessionBreakRepository workSessionBreakRepository;

    @Autowired
    private ExtPersonReplicaRepository extPersonReplicaRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private EmployeeLocationAssignmentRepository assignmentRepository;

    private static final UUID LOCATION_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private static final UUID PERSON_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID ISOLATED_PERSON_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final String START_PAYLOAD = """
			{
			  "personId": "%s",
			  "actor": "test-user"
			}
			""".formatted(PERSON_ID);

    @BeforeEach
    void cleanData() {
        workSessionBreakRepository.deleteAll();
        workSessionRepository.deleteAll();
        assignmentRepository.deleteAll();
        employeeRepository.deleteAll();
        ensurePersonExists(PERSON_ID, "Ava", "Technician");
        ensurePersonExists(ISOLATED_PERSON_ID, "Blake", "Technician");
    }

    /** Every request here is a supervisor's: the integration caller is linked to no person. */
    private MockHttpServletRequestBuilder asSupervisor(MockHttpServletRequestBuilder builder) {
        return withAuth(builder, SUPERVISOR_AUTHORITIES);
    }

    @Test
    @DisplayName("CP-120-001: start work session returns 200")
    void CP_120_001_startWorkSession_returns200() throws Exception {
        mockMvc.perform(asSupervisor(post("/v1/people/workSessions/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(START_PAYLOAD)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("CP-120-002: stop work session returns 200 and includes sessionId")
    void CP_120_002_stopWorkSession_returns200() throws Exception {
        mockMvc.perform(asSupervisor(post("/v1/people/workSessions/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(START_PAYLOAD)))
                .andExpect(status().isOk());

        mockMvc.perform(asSupervisor(post("/v1/people/workSessions/stop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(START_PAYLOAD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").exists());
    }

    @Test
    @DisplayName("CP-120-003: start break for valid session returns 200")
    void CP_120_003_startBreak_validSession_returns200() throws Exception {
        String response = mockMvc.perform(asSupervisor(post("/v1/people/workSessions/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(START_PAYLOAD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String sessionId = objectMapper.readTree(response).get("sessionId").asText();

        mockMvc.perform(asSupervisor(post("/v1/people/workSessions/" + sessionId + "/breaks/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("CP-120-004: stop break for valid session returns 200")
    void CP_120_004_stopBreak_validSession_returns200() throws Exception {
        String response = mockMvc.perform(asSupervisor(post("/v1/people/workSessions/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(START_PAYLOAD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String sessionId = objectMapper.readTree(response).get("sessionId").asText();

        mockMvc.perform(asSupervisor(post("/v1/people/workSessions/" + sessionId + "/breaks/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")))
                .andExpect(status().isOk());

        mockMvc.perform(asSupervisor(post("/v1/people/workSessions/" + sessionId + "/breaks/stop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("VE-120-001: starting a second active session returns 4xx")
    void VE_120_001_startWorkSession_whenAlreadyActive_returns4xx() throws Exception {
        String isolatedPayload = """
				{
				  "personId": "%s",
				  "actor": "test-user"
				}
				""".formatted(ISOLATED_PERSON_ID);

        mockMvc.perform(asSupervisor(post("/v1/people/workSessions/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(isolatedPayload)))
                .andExpect(status().isOk());

        mockMvc.perform(asSupervisor(post("/v1/people/workSessions/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(isolatedPayload)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("VE-120-002: starting break for non-existent session returns 404")
    void VE_120_002_startBreak_nonExistentSession_returns404() throws Exception {
        mockMvc.perform(asSupervisor(post(
                                "/v1/people/workSessions/{id}/breaks/start",
                                UUID.fromString("99999999-9999-9999-9999-999999999999"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("CP-120-005: submit an ended work session returns 200 with SUBMITTED status and totals")
    void CP_120_005_submitWorkSession_returns200() throws Exception {
        mockMvc.perform(asSupervisor(post("/v1/people/workSessions/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(START_PAYLOAD)))
                .andExpect(status().isOk());

        String stopResponse = mockMvc.perform(asSupervisor(post("/v1/people/workSessions/stop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(START_PAYLOAD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String sessionId = objectMapper.readTree(stopResponse).get("sessionId").asText();

        String submitPayload = """
				{
				  "billableMinutes": 450,
				  "breakMinutes": 30,
				  "submittedAt": "2026-01-01T16:05:00Z"
				}
				""";

        mockMvc.perform(asSupervisor(post("/v1/people/workSessions/" + sessionId + "/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitPayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.billableMinutes").value(450))
                .andExpect(jsonPath("$.breakMinutes").value(30));
    }

    @Test
    @DisplayName("VE-120-003: submitting a non-existent session returns 404")
    void VE_120_003_submitWorkSession_nonExistentSession_returns404() throws Exception {
        String submitPayload = """
				{
				  "billableMinutes": 60,
				  "breakMinutes": 0,
				  "submittedAt": "2026-01-01T16:05:00Z"
				}
				""";

        mockMvc.perform(asSupervisor(post(
                                "/v1/people/workSessions/{id}/submit",
                                UUID.fromString("99999999-9999-9999-9999-999999999999"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitPayload)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("VE-120-004: submitting an active (not ended) session returns 409")
    void VE_120_004_submitWorkSession_notEnded_returns409() throws Exception {
        String startResponse = mockMvc.perform(asSupervisor(post("/v1/people/workSessions/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(START_PAYLOAD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String sessionId = objectMapper.readTree(startResponse).get("sessionId").asText();

        String submitPayload = """
				{
				  "billableMinutes": 60,
				  "breakMinutes": 0,
				  "submittedAt": "2026-01-01T16:05:00Z"
				}
				""";

        mockMvc.perform(asSupervisor(post("/v1/people/workSessions/" + sessionId + "/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitPayload)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("VE-120-005: submitting negative billableMinutes returns 400")
    void VE_120_005_submitWorkSession_negativeMinutes_returns400() throws Exception {
        String response = mockMvc.perform(asSupervisor(post("/v1/people/workSessions/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(START_PAYLOAD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String sessionId = objectMapper.readTree(response).get("sessionId").asText();

        String invalidPayload = """
				{
				  "billableMinutes": -1,
				  "breakMinutes": 0,
				  "submittedAt": "2026-01-01T16:05:00Z"
				}
				""";

        mockMvc.perform(asSupervisor(post("/v1/people/workSessions/" + sessionId + "/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload)))
                .andExpect(status().isBadRequest());
    }

    // ---- Current clock state (issue #2061) --------------------------------------------------

    @Test
    @DisplayName("CP-120-006 (AC6): current state of a person with nothing open is 200 CLOCKED_OUT, not 404")
    void CP_120_006_currentWorkSession_whenNothingOpen_isClockedOut() throws Exception {
        mockMvc.perform(asSupervisor(get("/v1/people/workSessions/current").param("personId", PERSON_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personId").value(PERSON_ID.toString()))
                .andExpect(jsonPath("$.clockState").value("CLOCKED_OUT"))
                .andExpect(jsonPath("$.workSessionId").doesNotExist());
    }

    @Test
    @DisplayName("CP-120-007 (AC8): start, then availability shows CLOCKED_IN; stop, then CLOCKED_OUT")
    void CP_120_007_toggleRoundTrip_isVisibleOnAvailability() throws Exception {
        assignToLocation(PERSON_ID);
        MockHttpServletRequestBuilder availability =
                get("/v1/people/availability").param("locationId", LOCATION_ID.toString());

        mockMvc.perform(asSupervisor(availability))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].personId").value(PERSON_ID.toString()))
                .andExpect(jsonPath("$[0].clockState").value("CLOCKED_OUT"))
                .andExpect(jsonPath("$[0].workSessionId").doesNotExist());

        String started = mockMvc.perform(asSupervisor(post("/v1/people/workSessions/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(START_PAYLOAD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String sessionId = objectMapper.readTree(started).get("sessionId").asText();

        mockMvc.perform(asSupervisor(availability))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].clockState").value("CLOCKED_IN"))
                .andExpect(jsonPath("$[0].workSessionId").value(sessionId))
                .andExpect(jsonPath("$[0].clockedInAt").exists())
                .andExpect(jsonPath("$[0].breakStartedAt").doesNotExist());

        mockMvc.perform(asSupervisor(post("/v1/people/workSessions/" + sessionId + "/breaks/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")))
                .andExpect(status().isOk());

        mockMvc.perform(asSupervisor(get("/v1/people/workSessions/current").param("personId", PERSON_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clockState").value("ON_BREAK"))
                .andExpect(jsonPath("$.workSessionId").value(sessionId))
                .andExpect(jsonPath("$.breakStartedAt").exists());

        mockMvc.perform(asSupervisor(post("/v1/people/workSessions/stop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(START_PAYLOAD)))
                .andExpect(status().isOk());

        mockMvc.perform(asSupervisor(availability))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].clockState").value("CLOCKED_OUT"))
                .andExpect(jsonPath("$[0].workSessionId").doesNotExist());
    }

    @Test
    @DisplayName("CP-120-008 (OQ1): without people:timekeeping:view the availability row carries no clock state")
    void CP_120_008_availability_withoutTimekeepingView_omitsClockState() throws Exception {
        assignToLocation(PERSON_ID);
        mockMvc.perform(asSupervisor(post("/v1/people/workSessions/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(START_PAYLOAD)))
                .andExpect(status().isOk());

        mockMvc.perform(withAuth(get("/v1/people/availability").param("locationId", LOCATION_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].personId").value(PERSON_ID.toString()))
                .andExpect(jsonPath("$[0].clockState").doesNotExist())
                .andExpect(jsonPath("$[0].workSessionId").doesNotExist());
    }

    @Test
    @DisplayName("VE-120-006 (BR4): a caller who is neither the person nor an approver cannot clock them in")
    void VE_120_006_startWorkSession_withoutApprove_isForbidden() throws Exception {
        mockMvc.perform(withAuth(post("/v1/people/workSessions/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(START_PAYLOAD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("VE-120-007: current state of an unknown person is 404")
    void VE_120_007_currentWorkSession_unknownPerson_isNotFound() throws Exception {
        mockMvc.perform(asSupervisor(get("/v1/people/workSessions/current")
                        .param("personId", "99999999-9999-9999-9999-999999999999")))
                .andExpect(status().isNotFound());
    }

    private void assignToLocation(UUID personId) {
        Employee employee = employeeRepository
                .findByPersonId(personId)
                .orElseThrow(() -> new IllegalStateException("employee fixture missing for " + personId));
        assignmentRepository.save(EmployeeLocationAssignment.builder()
                .employee(employee)
                .locationId(LOCATION_ID)
                .role("TECHNICIAN")
                .isPrimary(true)
                .effectiveFrom(LocalDate.of(2026, 1, 1))
                .build());
    }

    private void ensurePersonExists(UUID personId, String firstName, String lastName) {
        if (extPersonReplicaRepository.existsById(personId)) {
            return;
        }
        extPersonReplicaRepository.save(ExtPersonReplica.builder()
                .personId(personId)
                .firstName(firstName)
                .lastName(lastName)
                .aggregateVersion(0)
                .updatedAt(java.time.Instant.now())
                .build());
        employeeRepository.save(Employee.builder()
                .personId(personId)
                .employeeNumber("EMP-" + personId.toString().substring(0, 8))
                .status(EmployeeStatus.ACTIVE)
                .build());
    }
}

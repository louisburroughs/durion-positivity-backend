package com.positivity.people.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.people.BaseContractIntegrationTest;
import com.positivity.people.internal.entity.Person;
import com.positivity.people.internal.enums.EmployeeStatus;
import com.positivity.people.internal.repository.PersonRepository;
import com.positivity.people.internal.repository.WorkSessionBreakRepository;
import com.positivity.people.internal.repository.WorkSessionRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

@DisplayName("Work Session ContractIT")
class WorkSessionContractIT extends BaseContractIntegrationTest {

    @Autowired
    private WorkSessionRepository workSessionRepository;

    @Autowired
    private WorkSessionBreakRepository workSessionBreakRepository;

    @Autowired
    private PersonRepository personRepository;

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
        ensurePersonExists(PERSON_ID, "Ava", "Technician");
        ensurePersonExists(ISOLATED_PERSON_ID, "Blake", "Technician");
    }

    @Test
    @DisplayName("CP-120-001: start work session returns 200")
    void CP_120_001_startWorkSession_returns200() throws Exception {
        mockMvc.perform(withAuth(post("/v1/people/workSessions/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(START_PAYLOAD)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("CP-120-002: stop work session returns 200 and includes sessionId")
    void CP_120_002_stopWorkSession_returns200() throws Exception {
        mockMvc.perform(withAuth(post("/v1/people/workSessions/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(START_PAYLOAD)))
                .andExpect(status().isOk());

        mockMvc.perform(withAuth(post("/v1/people/workSessions/stop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(START_PAYLOAD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").exists());
    }

    @Test
    @DisplayName("CP-120-003: start break for valid session returns 200")
    void CP_120_003_startBreak_validSession_returns200() throws Exception {
        String response = mockMvc.perform(withAuth(post("/v1/people/workSessions/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(START_PAYLOAD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String sessionId = objectMapper.readTree(response).get("sessionId").asText();

        mockMvc.perform(withAuth(post("/v1/people/workSessions/" + sessionId + "/breaks/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("CP-120-004: stop break for valid session returns 200")
    void CP_120_004_stopBreak_validSession_returns200() throws Exception {
        String response = mockMvc.perform(withAuth(post("/v1/people/workSessions/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(START_PAYLOAD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String sessionId = objectMapper.readTree(response).get("sessionId").asText();

        mockMvc.perform(withAuth(post("/v1/people/workSessions/" + sessionId + "/breaks/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")))
                .andExpect(status().isOk());

        mockMvc.perform(withAuth(post("/v1/people/workSessions/" + sessionId + "/breaks/stop")
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

        mockMvc.perform(withAuth(post("/v1/people/workSessions/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(isolatedPayload)))
                .andExpect(status().isOk());

        mockMvc.perform(withAuth(post("/v1/people/workSessions/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(isolatedPayload)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("VE-120-002: starting break for non-existent session returns 404")
    void VE_120_002_startBreak_nonExistentSession_returns404() throws Exception {
        mockMvc.perform(withAuth(post(
                                "/v1/people/workSessions/{id}/breaks/start",
                                UUID.fromString("99999999-9999-9999-9999-999999999999"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("CP-120-005: submit an ended work session returns 200 with SUBMITTED status and totals")
    void CP_120_005_submitWorkSession_returns200() throws Exception {
        mockMvc.perform(withAuth(post("/v1/people/workSessions/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(START_PAYLOAD)))
                .andExpect(status().isOk());

        String stopResponse = mockMvc.perform(withAuth(post("/v1/people/workSessions/stop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(START_PAYLOAD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String sessionId = objectMapper.readTree(stopResponse).get("sessionId").asText();

        String submitPayload =
                """
				{
				  "billableMinutes": 450,
				  "breakMinutes": 30,
				  "submittedAt": "2026-01-01T16:05:00Z"
				}
				""";

        mockMvc.perform(withAuth(post("/v1/people/workSessions/" + sessionId + "/submit")
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
        String submitPayload =
                """
				{
				  "billableMinutes": 60,
				  "breakMinutes": 0,
				  "submittedAt": "2026-01-01T16:05:00Z"
				}
				""";

        mockMvc.perform(withAuth(post(
                                "/v1/people/workSessions/{id}/submit",
                                UUID.fromString("99999999-9999-9999-9999-999999999999"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitPayload)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("VE-120-004: submitting an active (not ended) session returns 409")
    void VE_120_004_submitWorkSession_notEnded_returns409() throws Exception {
        String startResponse = mockMvc.perform(withAuth(post("/v1/people/workSessions/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(START_PAYLOAD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String sessionId = objectMapper.readTree(startResponse).get("sessionId").asText();

        String submitPayload =
                """
				{
				  "billableMinutes": 60,
				  "breakMinutes": 0,
				  "submittedAt": "2026-01-01T16:05:00Z"
				}
				""";

        mockMvc.perform(withAuth(post("/v1/people/workSessions/" + sessionId + "/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitPayload)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("VE-120-005: submitting negative billableMinutes returns 400")
    void VE_120_005_submitWorkSession_negativeMinutes_returns400() throws Exception {
        String response = mockMvc.perform(withAuth(post("/v1/people/workSessions/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(START_PAYLOAD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String sessionId = objectMapper.readTree(response).get("sessionId").asText();

        String invalidPayload =
                """
				{
				  "billableMinutes": -1,
				  "breakMinutes": 0,
				  "submittedAt": "2026-01-01T16:05:00Z"
				}
				""";

        mockMvc.perform(withAuth(post("/v1/people/workSessions/" + sessionId + "/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload)))
                .andExpect(status().isBadRequest());
    }

    private void ensurePersonExists(UUID personId, String firstName, String lastName) {
        if (personRepository.existsById(personId)) {
            return;
        }
        personRepository.save(Person.builder()
                .id(personId)
                .firstName(firstName)
                .lastName(lastName)
                .primaryEmail(firstName.toLowerCase() + "." + lastName.toLowerCase() + "@example.com")
                .employeeNumber("EMP-" + personId.toString().substring(0, 8))
                .status(EmployeeStatus.ACTIVE)
                .build());
    }
}

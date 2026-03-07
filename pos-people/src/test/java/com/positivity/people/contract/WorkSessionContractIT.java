package com.positivity.people.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.people.BaseContractIntegrationTest;
import com.positivity.people.internal.repository.WorkSessionBreakRepository;
import com.positivity.people.internal.repository.WorkSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import java.util.UUID;

@DisplayName("Work Session ContractIT")
class WorkSessionContractIT extends BaseContractIntegrationTest {

	@Autowired
	private WorkSessionRepository workSessionRepository;

	@Autowired
	private WorkSessionBreakRepository workSessionBreakRepository;

	private static final UUID PERSON_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

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
	}

	@Test
	@DisplayName("CP-120-001: start work session returns 200")
	void CP_120_001_startWorkSession_returns200() throws Exception {
		mockMvc
			.perform(withAuth(post("/v1/people/workSessions/start").contentType(MediaType.APPLICATION_JSON)
				.content(START_PAYLOAD)))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("CP-120-002: stop work session returns 200 and includes sessionId")
	void CP_120_002_stopWorkSession_returns200() throws Exception {
		mockMvc
			.perform(withAuth(post("/v1/people/workSessions/start").contentType(MediaType.APPLICATION_JSON)
				.content(START_PAYLOAD)))
			.andExpect(status().isOk());

		mockMvc
			.perform(withAuth(post("/v1/people/workSessions/stop").contentType(MediaType.APPLICATION_JSON)
				.content(START_PAYLOAD)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sessionId").exists());
	}

	@Test
	@DisplayName("CP-120-003: start break for valid session returns 200")
	void CP_120_003_startBreak_validSession_returns200() throws Exception {
		String response = mockMvc
			.perform(withAuth(post("/v1/people/workSessions/start").contentType(MediaType.APPLICATION_JSON)
				.content(START_PAYLOAD)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		String sessionId = objectMapper.readTree(response).get("sessionId").asText();

		mockMvc
			.perform(withAuth(post("/v1/people/workSessions/" + sessionId + "/breaks/start")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}")))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("CP-120-004: stop break for valid session returns 200")
	void CP_120_004_stopBreak_validSession_returns200() throws Exception {
		String response = mockMvc
			.perform(withAuth(post("/v1/people/workSessions/start").contentType(MediaType.APPLICATION_JSON)
				.content(START_PAYLOAD)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		String sessionId = objectMapper.readTree(response).get("sessionId").asText();

		mockMvc
			.perform(withAuth(post("/v1/people/workSessions/" + sessionId + "/breaks/start")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}")))
			.andExpect(status().isOk());

		mockMvc
			.perform(withAuth(post("/v1/people/workSessions/" + sessionId + "/breaks/stop")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}")))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("VE-120-001: starting a second active session returns 4xx")
	void VE_120_001_startWorkSession_whenAlreadyActive_returns4xx() throws Exception {
		UUID isolatedPersonId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
		String isolatedPayload = """
				{
				  "personId": "%s",
				  "actor": "test-user"
				}
				""".formatted(isolatedPersonId);

		mockMvc
			.perform(withAuth(post("/v1/people/workSessions/start").contentType(MediaType.APPLICATION_JSON)
				.content(isolatedPayload)))
			.andExpect(status().isOk());

		mockMvc
			.perform(withAuth(post("/v1/people/workSessions/start").contentType(MediaType.APPLICATION_JSON)
				.content(isolatedPayload)))
			.andExpect(status().is4xxClientError());
	}

	@Test
	@DisplayName("VE-120-002: starting break for non-existent session returns 404")
	void VE_120_002_startBreak_nonExistentSession_returns404() throws Exception {
		mockMvc
			.perform(withAuth(post("/v1/people/workSessions/{id}/breaks/start",
					UUID.fromString("99999999-9999-9999-9999-999999999999"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}")))
			.andExpect(status().isNotFound());
	}

}

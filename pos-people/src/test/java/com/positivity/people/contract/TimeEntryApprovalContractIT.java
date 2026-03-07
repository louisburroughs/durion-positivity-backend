package com.positivity.people.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.people.BaseContractIntegrationTest;
import com.positivity.people.internal.entity.Person;
import com.positivity.people.internal.entity.TimeEntry;
import com.positivity.people.internal.enums.TimeEntryStatus;
import com.positivity.people.internal.repository.TimeEntryRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

@DisplayName("Time Entry Approval ContractIT")
class TimeEntryApprovalContractIT extends BaseContractIntegrationTest {

	@Autowired
	private TimeEntryRepository timeEntryRepository;

	@Test
	@DisplayName("CP-120-020: batch approve returns 200")
	void CP_120_020_batchApprove_returns200() throws Exception {
		UUID timeEntryId = seedSubmittedTimeEntry();
		String payload = """
				{
				  "decisions": [
				    { "timeEntryId": "%s" }
				  ]
				}
				""".formatted(timeEntryId);

		mockMvc
			.perform(withAuth(post("/v1/people/timeEntries/approve").contentType(MediaType.APPLICATION_JSON)
				.header("X-Permissions", "people:timeEntry:approve")
				.header("X-User-Id", TEST_USER)
				.content(payload)))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("CP-120-021: batch reject with reason returns 200")
	void CP_120_021_batchReject_withReason_returns200() throws Exception {
		UUID timeEntryId = seedSubmittedTimeEntry();
		String payload = """
				{
				  "decisions": [
				    { "timeEntryId": "%s", "rejectionReason": "Late submission" }
				  ]
				}
				""".formatted(timeEntryId);

		mockMvc
			.perform(withAuth(post("/v1/people/timeEntries/reject").contentType(MediaType.APPLICATION_JSON)
				.header("X-Permissions", "people:timeEntry:reject")
				.header("X-User-Id", TEST_USER)
				.content(payload)))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("VE-120-020: batch reject missing reason returns 400")
	void VE_120_020_batchReject_missingReason_returns400() throws Exception {
		UUID timeEntryId = seedSubmittedTimeEntry();
		String payload = """
				{
				  "decisions": [
				    { "timeEntryId": "%s" }
				  ]
				}
				""".formatted(timeEntryId);

		mockMvc
			.perform(withAuth(
					post("/v1/people/timeEntries/reject").contentType(MediaType.APPLICATION_JSON).content(payload)))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("VE-120-021: batch approve empty decisions returns 400")
	void VE_120_021_batchApprove_emptyDecisions_returns400() throws Exception {
		String payload = """
				{
				  "decisions": []
				}
				""";

		mockMvc
			.perform(withAuth(
					post("/v1/people/timeEntries/approve").contentType(MediaType.APPLICATION_JSON).content(payload)))
			.andExpect(status().isBadRequest());
	}

	private UUID seedSubmittedTimeEntry() {
		TimeEntry timeEntry = new TimeEntry();
		timeEntry.setPerson(Person.builder().id(UUID.fromString("00000000-0000-0000-0000-000000000001")).build());
		timeEntry.setTimesheetId("timesheet-2");
		timeEntry.setStatus(TimeEntryStatus.SUBMITTED);
		return timeEntryRepository.save(timeEntry).getTimeEntryId();
	}

}

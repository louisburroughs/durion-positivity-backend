package com.positivity.people.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.people.BaseContractIntegrationTest;
import com.positivity.people.internal.entity.TimeEntry;
import com.positivity.people.internal.enums.TimeEntryStatus;
import com.positivity.people.internal.repository.TimeEntryRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

@DisplayName("Time Entry Adjustment ContractIT")
class TimeEntryAdjustmentContractIT extends BaseContractIntegrationTest {

    @Autowired
    private TimeEntryRepository timeEntryRepository;

    @Test
    @DisplayName("CP-120-010: create adjustment returns 201")
    void CP_120_010_createAdjustment_returns201() throws Exception {
        UUID timeEntryId = seedPendingApprovalTimeEntry();

        String payload = """
                {
                  "timeEntryId": "%s",
                  "reasonCode": "MISSED_BREAK",
                  "minutesDelta": 15,
                  "createdBy": "integration-test-user"
                }
                """.formatted(timeEntryId);

        mockMvc.perform(withAuth(post("/v1/people/timeEntries/adjustments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.adjustmentId").exists());
    }

    @Test
    @DisplayName("CP-120-012: list adjustments returns 200")
    void CP_120_012_listAdjustments_returns200() throws Exception {
        UUID timeEntryId = seedPendingApprovalTimeEntry();

        mockMvc.perform(withAuth(get("/v1/people/timeEntries/{timeEntryId}/adjustments", timeEntryId)
                .accept(MediaType.APPLICATION_JSON)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("VE-120-010: create adjustment missing required fields returns 400")
    void VE_120_010_createAdjustment_missingRequired_returns400() throws Exception {
        mockMvc.perform(withAuth(post("/v1/people/timeEntries/adjustments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("VE-120-011: approve adjustment not found returns 404")
    void VE_120_011_approveAdjustment_notFound_returns404() throws Exception {
        mockMvc.perform(withAuth(post("/v1/people/timeEntries/adjustments/{id}/approve",
                UUID.fromString("00000000-0000-0000-0000-000000000000"))
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Permissions", "people:timeAdjustment:approve")
                .header("X-User-Id", TEST_USER)
                .content("{}")))
                .andExpect(status().isNotFound());
    }

    private UUID seedPendingApprovalTimeEntry() {
        TimeEntry timeEntry = new TimeEntry();
        timeEntry.setPersonId("person-1");
        timeEntry.setTimesheetId("timesheet-1");
        timeEntry.setStatus(TimeEntryStatus.PENDING_APPROVAL);
        return timeEntryRepository.save(timeEntry).getTimeEntryId();
    }
}

package com.positivity.people.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.people.BaseIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

@DisplayName("Time Entry Exception ContractIT")
class TimeEntryExceptionContractIT extends BaseIntegrationTest {

    @Test
    @DisplayName("CP-120-030: create exception returns 200")
    void CP_120_030_createException_returns200() throws Exception {
        String payload = """
                {
                  "employeeId": "EMP-001",
                  "exceptionCode": "MISSED_CLOCK_OUT"
                }
                """;

        mockMvc.perform(withAuth(post("/v1/people/exceptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("CP-120-034: list exceptions returns 200")
    void CP_120_034_listExceptions_returns200() throws Exception {
        mockMvc.perform(withAuth(get("/v1/people/exceptions")
                .accept(MediaType.APPLICATION_JSON)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("CP-120-034: list exceptions by employee returns 200")
    void CP_120_034_listExceptions_byEmployee_returns200() throws Exception {
        mockMvc.perform(withAuth(get("/v1/people/exceptions")
                .param("employeeId", "EMP-001")
                .accept(MediaType.APPLICATION_JSON)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("VE-120-030: waive exception missing reason returns 400")
    void VE_120_030_waiveException_missingReason_returns400() throws Exception {
        mockMvc.perform(withAuth(post("/v1/people/exceptions/{id}/waive",
                UUID.fromString("00000000-0000-0000-0000-000000000000"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("VE-120-031: waive exception not found returns 404")
    void VE_120_031_waiveException_notFound_returns404() throws Exception {
        String payload = """
                {
                  "waiveReason": "test"
                }
                """;

        mockMvc.perform(withAuth(post("/v1/people/exceptions/{id}/waive",
                UUID.fromString("00000000-0000-0000-0000-000000000000"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)))
                .andExpect(status().isNotFound());
    }
}

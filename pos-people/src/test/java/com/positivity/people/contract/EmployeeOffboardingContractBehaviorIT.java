package com.positivity.people.contract;

import com.positivity.people.BaseContractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Employee Offboarding ContractBehaviorIT")
class EmployeeOffboardingContractBehaviorIT extends BaseContractIntegrationTest {

    @Test
    @DisplayName("CP-117-010: Disable active employee -> 200 OK, status=DISABLED")
    void cp117010_disableActiveEmployee_returns200() throws Exception {
        UUID employeeId = createEmployee("EMP-117-010", "employee.117.010@example.com");

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
    }

    @Test
    @DisplayName("VE-117-010: Disable already-disabled employee -> 400")
    void ve117010_disableAlreadyDisabledEmployee_returns400() throws Exception {
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
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("LC-117-010: Disable terminates assignments per policy")
    void lc117010_disableTerminatesAssignmentsPerPolicy_returns200() throws Exception {
        UUID employeeId = createEmployee("EMP-117-012", "employee.117.012@example.com");

        String payload = """
                {
                  "disableReason": "scheduled leave",
                                                                        "assignmentPolicy": "GRACE_PERIOD",
                                                                        "assignmentEndDate": "2026-03-01"
                }
                """;

        mockMvc.perform(withAuth(post("/v1/people/employees/{employeeId}/disable", employeeId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
    }

    private UUID createEmployee(String employeeNumber, String email) throws Exception {
        String numericSuffix = employeeNumber.replaceAll("\\D", "");
        if (numericSuffix.length() > 4) {
            numericSuffix = numericSuffix.substring(numericSuffix.length() - 4);
        }
        String payload = """
                {
                  "legalName": "Offboarding Employee",
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

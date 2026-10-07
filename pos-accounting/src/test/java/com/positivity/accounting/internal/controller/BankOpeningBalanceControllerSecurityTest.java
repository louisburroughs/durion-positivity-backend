package com.positivity.accounting.internal.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The permission gate and request checks of the bank opening balance endpoint (#2572): the service is never reached
 * without both accounting:je:create and accounting:je:post, and a malformed body is 400 VALIDATION_ERROR. Behaviour
 * behind the gate is covered by BankOpeningBalancePostgresIT.
 */
@DisplayName("Bank opening balance endpoint: permissions (#2572)")
class BankOpeningBalanceControllerSecurityTest extends BaseIntegrationTest {

    private static final String OPENING =
            "/v1/accounting/bank-accounts/019a0000-0000-7000-8000-00000000b000" + "/opening-balance";
    private static final String BODY = """
            {"asOfDate":"2025-10-31","statementBalance":10000.00,"currencyCode":"USD",
             "outstandingItems":[{"type":"OUTSTANDING_CHECK","reference":"1043","itemDate":"2025-10-28","amount":450.00}],
             "justification":"Opening balance per the October bank statement",
             "requestId":"019a0000-0000-7000-8000-000000000201"}
            """;

    @Test
    @DisplayName("403 with only one of accounting:je:create and accounting:je:post, or neither")
    void needsBothPermissions() throws Exception {
        for (String authorities : new String[] {"accounting:je:view", "accounting:je:create", "accounting:je:post"}) {
            mockMvc.perform(post(OPENING)
                            .header("X-User", TEST_USER)
                            .header("X-Authorities", authorities)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("a short justification, an itemDate after asOfDate, a non-positive amount, or a missing or non-ISO"
            + " currencyCode is 400 VALIDATION_ERROR")
    void bodyIsValidated() throws Exception {
        // Each malformed body and the field its fieldErrors names.
        List<Map.Entry<String, String>> bodies = List.of(
                Map.entry("justification", BODY.replace("Opening balance per the October bank statement", "short")),
                Map.entry(
                        "outstandingItems[0].itemDate",
                        BODY.replace("\"itemDate\":\"2025-10-28\"", "\"itemDate\":\"2025-11-01\"")),
                Map.entry("outstandingItems[0].amount", BODY.replace("\"amount\":450.00", "\"amount\":0")),
                Map.entry("currencyCode", BODY.replace("\"currencyCode\":\"USD\",", "")),
                Map.entry("currencyCode", BODY.replace("\"currencyCode\":\"USD\"", "\"currencyCode\":\"XYZ\"")));
        for (Map.Entry<String, String> body : bodies) {
            mockMvc.perform(post(OPENING)
                            .header("X-User", TEST_USER)
                            .header("X-Authorities", "accounting:je:create,accounting:je:post")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body.getValue()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value(body.getKey()));
        }
    }
}

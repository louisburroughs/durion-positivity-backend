package com.positivity.accounting.internal.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The permission gates and request checks of the register float and petty-expense category endpoints (#2511
 * AC 7): the service is never reached without the permission §4.6 names, and a malformed body is 400
 * VALIDATION_ERROR. Behaviour behind the gates is covered by CashSetupPostgresIT.
 */
@DisplayName("Register float and petty-expense category endpoints: permissions (#2511)")
class CashSetupControllerSecurityTest extends BaseIntegrationTest {

    private static final String GO_LIVE = "/v1/accounting/registers/T-1/float/go-live";
    private static final String CHANGE = "/v1/accounting/registers/T-1/float";
    private static final String CATEGORIES = "/v1/accounting/petty-expense-categories";
    private static final String GO_LIVE_BODY = """
            {"locationId":"019a0000-0000-7000-8000-00000000a001","amount":200.00,"goLiveDate":"2026-10-01",
             "justification":"Counted float in drawer 1 at go-live","requestId":"019a0000-0000-7000-8000-000000000101"}
            """;
    private static final String CHANGE_BODY = """
            {"locationId":"019a0000-0000-7000-8000-00000000a001","amount":300.00,
             "bankGlAccountId":"019a0000-0000-7000-8000-00000000b000",
             "justification":"More change for the weekend","requestId":"019a0000-0000-7000-8000-000000000102"}
            """;
    private static final String CREATE_BODY = """
            {"code":"TIRE_DISPOSAL","label":"Tire disposal","glAccountId":"019a0000-0000-7000-8000-00000000c000",
             "justification":"Cashiers pay the scrap hauler","requestId":"019a0000-0000-7000-8000-000000000103"}
            """;

    @Test
    @DisplayName("AC7: both float commands answer 403 without accounting:float:manage")
    void floatCommandsNeedFloatManage() throws Exception {
        mockMvc.perform(post(GO_LIVE)
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", TEST_AUTHORITIES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(GO_LIVE_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(CHANGE)
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", TEST_AUTHORITIES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CHANGE_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a go-live body with a short justification is 400 VALIDATION_ERROR")
    void goLiveBodyIsValidated() throws Exception {
        mockMvc.perform(post(GO_LIVE)
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", "accounting:float:manage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(GO_LIVE_BODY.replace("Counted float in drawer 1 at go-live", "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("category reads and writes need the mapping-key and gl-mapping permissions §4.6 names")
    void categoryEndpointsAreGated() throws Exception {
        mockMvc.perform(get(CATEGORIES).header("X-User", TEST_USER).header("X-Authorities", "accounting:coa:view"))
                .andExpect(status().isForbidden());
        // Create needs both mapping-key:create and gl-mapping:create.
        mockMvc.perform(post(CATEGORIES)
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", "accounting:mapping-key:create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(CATEGORIES + "/STAFF_MEALS")
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", "accounting:mapping-key:view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Staff meals\",\"justification\":\"Clearer label please\","
                                + "\"requestId\":\"019a0000-0000-7000-8000-000000000104\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(CATEGORIES + "/STAFF_MEALS/deactivate")
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", "accounting:mapping-key:edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"justification\":\"No longer needed\","
                                + "\"requestId\":\"019a0000-0000-7000-8000-000000000105\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(CATEGORIES + "/STAFF_MEALS/account")
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", "accounting:mapping-key:edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"glAccountId\":\"019a0000-0000-7000-8000-00000000c000\",\"effectiveFrom\":"
                                + "\"2026-11-01\",\"justification\":\"Own account from November\","
                                + "\"requestId\":\"019a0000-0000-7000-8000-000000000106\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a create with a code outside [A-Z0-9_]{1,40} is 400 VALIDATION_ERROR")
    void categoryCodeIsValidated() throws Exception {
        mockMvc.perform(post(CATEGORIES)
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", "accounting:mapping-key:create,accounting:gl-mapping:create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("TIRE_DISPOSAL", "tire disposal")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}

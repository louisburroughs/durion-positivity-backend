package com.positivity.workorder.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.workorder.support.BaseContractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Negative-path authorization tests for the endpoints tightened in #1432 and #1433.
 *
 * <p>Every request here authenticates successfully but carries an authority set that lacks the one
 * permission the endpoint requires, pinning the enforcement to the specific authority rather than
 * to authentication. The happy paths are covered by the other contract ITs, which run with the full
 * {@code TEST_AUTHORITIES} set.
 */
@DisplayName("Authorization Enforcement Contract Behavior Tests (#1432, #1433)")
@Import(ContractTestConfiguration.class)
class AuthorizationEnforcementContractBehaviorIT extends BaseContractIntegrationTest {

    private static final UUID ANY_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");

    /** Authenticated with real workorder authorities, just not the one under test. */
    private MockHttpServletRequestBuilder withAuthorities(MockHttpServletRequestBuilder req, String authorities) {
        return req.header("Authorization", "Bearer " + TEST_BEARER_TOKEN)
                .header("X-User-Id", SYSTEM_USER_ID)
                .header("X-User", SYSTEM_USER_ID)
                .header("X-Authorities", authorities);
    }

    @Test
    @DisplayName("AE-001: createWorkorder without workorder:workorder:create returns 403")
    void createWorkorder_withoutCreateAuthority_returnsForbidden() throws Exception {
        String body = "{\"estimateId\":\"" + ANY_ID + "\",\"customerId\":\"" + ANY_ID + "\"}";

        mockMvc.perform(withAuthorities(post("/v1/workorders"), "workorder:workorder:view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AE-002: deleteWorkorder without workorder:workorder:delete returns 403")
    void deleteWorkorder_withoutDeleteAuthority_returnsForbidden() throws Exception {
        mockMvc.perform(withAuthorities(
                        delete("/v1/workorders/{workorderId}", ANY_ID),
                        "workorder:workorder:view,workorder:workorder:create"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AE-003: getWorkorderTransitions without workorder:workorder:view returns 403")
    void getTransitionHistory_withoutViewAuthority_returnsForbidden() throws Exception {
        mockMvc.perform(withAuthorities(
                        get("/v1/workorders/{workorderId}/transitions", ANY_ID), "workorder:workorder:create"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AE-004: getWorkorderSnapshots without workorder:workorder:view returns 403")
    void getSnapshotHistory_withoutViewAuthority_returnsForbidden() throws Exception {
        mockMvc.perform(withAuthorities(
                        get("/v1/workorders/{workorderId}/snapshots", ANY_ID), "workorder:workorder:create"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AE-005: createEstimateFromAppointment without workorder:estimate:create returns 403")
    void createEstimateFromAppointment_withoutEstimateCreateAuthority_returnsForbidden() throws Exception {
        String body = "{\"idempotencyKey\":\"" + ANY_ID + "\",\"appointmentId\":\"" + ANY_ID + "\",\"customerId\":\""
                + ANY_ID + "\",\"vehicleId\":\"" + ANY_ID + "\",\"locationId\":\"" + ANY_ID + "\"}";

        mockMvc.perform(withAuthorities(post("/v1/workorders/estimates/from-appointment"), "workorder:estimate:view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }
}

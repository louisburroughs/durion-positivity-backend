package com.positivity.customer.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.enums.AccountStatus;
import com.positivity.customer.internal.enums.PartyType;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contract behavior integration tests for the Billing Rules endpoint.
 * Covers behavioral assertions BR-001 through BR-004 for CAP-252 Story #3
 * (Billing Rules Endpoint).
 *
 * <p>
 * ADR compliance:
 * <ul>
 * <li>ADR-0025: Permission registration source is module
 * {@code permissions.yaml}; enforced authority here is
 * {@code crm:party:view}</li>
 * <li>ADR-0009: pos-customer owns billing rule surface; enforcement is caller
 * responsibility</li>
 * <li>ADR-0011 + ADR-0014: Auth via gateway headers
 * {@code X-Authorities: crm:party:view};
 * {@code GatewayAuthoritiesFilter} clears SecurityContextHolder when headers
 * absent, so {@code @WithMockUser} is not used — header-based auth is the only
 * supported pattern.</li>
 * <li>ADR-0017: HTTP status codes — 200 success, 404 not found, 401
 * unauthenticated, 403 forbidden</li>
 * </ul>
 *
 * <p>
 * RED phase notes: BR-001 and BR-002 will fail because the endpoint
 * {@code GET /v1/crm/snapshot/party/{partyId}/billing-rules} does not yet
 * exist. Spring MVC returns 404 (no handler found) rather than 200.
 * BR-003 and BR-004 pass coincidentally during RED because 404 and 403 already
 * match their expectations (no endpoint + GatewayAuthoritiesFilter behavior).
 *
 * Issue: CAP-252
 */
@Transactional
class BillingRulesContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private CommercialPartyRepository partyRepository;

    /** X-User header value for all authorized test requests. */
    private static final String TEST_USER = "billing-rules-test-user";

    /**
     * Authority header value granting PARTY_VIEW (ADR-0025 policy).
     * Uses the canonical string form from CrmPermissionRegistry.
     */
    private static final String PARTY_VIEW_AUTH = "crm:party:view";

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Creates and persists a minimal {@link CommercialParty} for billing-rules
     * tests. Satisfies the {@code @NotEmpty} contacts constraint and all required
     * non-null fields.
     *
     * @return the saved entity with a generated partyId
     */
    private CommercialParty createTestParty() {
        CommercialParty party = new CommercialParty();
        party.setPartyType(PartyType.COMMERCIAL);
        party.setLegalName("Billing Rules Test Corp");
        party.setDisplayName("Billing Corp");
        party.setCustomerNumber("CUST-BR-"
                + UUID.fromString("00000000-0000-0000-0000-000000000001")
                        .toString()
                        .substring(0, 8));
        party.setStatus(AccountStatus.ACTIVE);

        return partyRepository.saveAndFlush(party);
    }

    // -------------------------------------------------------------------------
    // BR-001 — Dedicated billing-rules endpoint returns 200 with poRequired field
    // -------------------------------------------------------------------------

    /**
     * BR-001: {@code GET /v1/crm/snapshot/party/{partyId}/billing-rules} returns
     * 200 with a {@code $.poRequired} boolean field in the response body.
     *
     * <p>
     * EXPECTED FAILURE (RED): endpoint does not exist → Spring MVC returns 404
     * (no handler found); test expects 200.
     *
     * Issue: CAP-252
     */
    @Test
    @DisplayName("BR-001: billing-rules returns 200 with poRequired field present")
    void billingRules_returns200_withPoRequired() throws Exception {
        CommercialParty party = createTestParty();

        // Issue CAP-252: endpoint missing → 404 returned instead of 200 (RED)
        mockMvc.perform(get("/v1/crm/snapshot/party/{partyId}/billing-rules", party.getPartyId())
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", PARTY_VIEW_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poRequired").isBoolean());
    }

    // -------------------------------------------------------------------------
    // BR-002 — Default billing rules have poRequired = false
    // -------------------------------------------------------------------------

    /**
     * BR-002: When a party has no explicit billing configuration,
     * {@code $.poRequired} defaults to {@code false}.
     *
     * <p>
     * EXPECTED FAILURE (RED): endpoint does not exist → Spring MVC returns 404
     * (no handler found); test expects 200 with poRequired = false.
     *
     * Issue: CAP-252
     */
    @Test
    @DisplayName("BR-002: billing-rules returns poRequired=false when none configured")
    void billingRules_returns200_withDefaults_whenNoneConfigured() throws Exception {
        CommercialParty party = createTestParty();

        // Issue CAP-252: endpoint missing → 404 returned instead of 200 (RED)
        mockMvc.perform(get("/v1/crm/snapshot/party/{partyId}/billing-rules", party.getPartyId())
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", PARTY_VIEW_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poRequired").value(false));
    }

    // -------------------------------------------------------------------------
    // BR-003 — Returns 404 for unknown partyId
    // -------------------------------------------------------------------------

    /**
     * BR-003: A request for a non-existent partyId returns 404.
     *
     * <p>
     * During RED phase this test passes coincidentally: the endpoint does not
     * exist, so Spring MVC returns 404 for any partyId, satisfying the assertion.
     * After GREEN implementation, the 404 must be driven by service-level not-found
     * logic, not a missing route.
     *
     * Issue: CAP-252
     */
    @Test
    @DisplayName("BR-003: billing-rules returns 404 when partyId not found")
    void billingRules_returns404_whenPartyNotFound() throws Exception {
        UUID unknownPartyId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        mockMvc.perform(get("/v1/crm/snapshot/party/{partyId}/billing-rules", unknownPartyId)
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", PARTY_VIEW_AUTH))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // BR-004 — Returns 401 when caller is unauthenticated
    // -------------------------------------------------------------------------

    /**
     * BR-004: A request without gateway auth headers returns 401 Unauthorized.
     *
     * <p>
     * Per ADR-0014, {@code GatewayAuthoritiesFilter} clears the
     * SecurityContextHolder when {@code X-Authorities} is absent, causing
     * and the security entry point to reject the request with 401 before method
     * security evaluation.
     *
     * Issue: CAP-252
     */
    @Test
    @DisplayName("BR-004: billing-rules returns 401 when caller is unauthenticated")
    void billingRules_returns401_whenUnauthenticated() throws Exception {
        CommercialParty party = createTestParty();

        // No X-Authorities/X-User headers → GatewayAuthoritiesFilter clears context
        mockMvc.perform(get("/v1/crm/snapshot/party/{partyId}/billing-rules", party.getPartyId()))
                .andExpect(status().isUnauthorized());
    }
}

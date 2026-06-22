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
 * Contract behavior integration tests for CRM Snapshot endpoint.
 * Covers behavioral assertions SS-001 through SS-007 for CAP-252 Story #4
 * (Customer Snapshot).
 *
 * <p>
 * ADR compliance:
 * <ul>
 * <li>ADR-0025: Permission registration source is module
 * {@code permissions.yaml}; enforced authority here is
 * {@code crm:party:view}</li>
 * <li>ADR-0011 + ADR-0014: Auth via gateway headers
 * {@code X-Authorities: crm:party:view};
 * {@code GatewayAuthoritiesFilter} clears SecurityContextHolder when headers
 * absent,
 * so {@code @WithMockUser} is not used — header-based auth is the only
 * supported pattern.</li>
 * <li>ADR-0017: HTTP status codes — 200 success, 404 not found, 401
 * unauthenticated, 403 forbidden</li>
 * </ul>
 *
 * Issue: CAP-252
 */
@Transactional
class CrmSnapshotContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private CommercialPartyRepository partyRepository;

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /** X-User header value used for all authorized test requests. */
    private static final String TEST_USER = "snapshot-test-user";

    /** X-Authorities header value granting PARTY_VIEW (ADR-0025 policy). */
    private static final String PARTY_VIEW_AUTH = "crm:party:view";

    private CommercialParty createTestParty() {
        CommercialParty party = new CommercialParty();
        party.setPartyType(PartyType.COMMERCIAL);
        party.setLegalName("Snapshot Test Corp");
        party.setDisplayName("Snapshot Corp");
        party.setCustomerNumber("CUST-SNAP-"
                + UUID.fromString("00000000-0000-0000-0000-000000000001")
                        .toString()
                        .substring(0, 8));
        party.setStatus(AccountStatus.ACTIVE);
        return partyRepository.saveAndFlush(party);
    }

    // -------------------------------------------------------------------------
    // SS-001 — Full snapshot with all sections present, including billingRules
    // -------------------------------------------------------------------------

    /**
     * SS-001: GET /v1/crm/snapshot/party/{partyId} returns 200 with all top-level
     * sections present, including the new billingRules section.
     *
     * <p>
     * EXPECTED FAILURE (RED): {@code $.billingRules} is currently null because
     * {@code assembleSnapshot()} does not populate the billingRules field.
     *
     * Issue: CAP-252
     */
    @Test
    @DisplayName("SS-001: snapshot returns 200 with account and billingRules present")
    void snapshotByParty_returns200_withAllSections() throws Exception {
        CommercialParty party = createTestParty();

        // Issue CAP-252: billingRules must not be null — currently fails RED
        mockMvc.perform(get("/v1/crm/snapshot/party/{partyId}", party.getPartyId())
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", PARTY_VIEW_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account").isNotEmpty())
                .andExpect(jsonPath("$.billingRules").isNotEmpty());
    }

    // -------------------------------------------------------------------------
    // SS-002 — billingRules.poRequired field is present
    // -------------------------------------------------------------------------

    /**
     * SS-002: billingRules section exposes a poRequired boolean field.
     *
     * <p>
     * EXPECTED FAILURE (RED): billingRules is null, so jsonPath yields no result.
     *
     * Issue: CAP-252
     */
    @Test
    @DisplayName("SS-002: snapshot billingRules.poRequired field is present")
    void snapshotByParty_billingRules_poRequired_isPresent() throws Exception {
        CommercialParty party = createTestParty();

        // Issue CAP-252: billingRules.poRequired must exist — currently fails RED
        mockMvc.perform(get("/v1/crm/snapshot/party/{partyId}", party.getPartyId())
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", PARTY_VIEW_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billingRules.poRequired").exists());
    }

    // -------------------------------------------------------------------------
    // SS-003 — billingRules defaults when no billing configuration exists
    // -------------------------------------------------------------------------

    /**
     * SS-003: When no billing rules are configured for a party, the snapshot must
     * surface safe defaults: poRequired=false, taxExempt=false,
     * paymentTerms="Due on Receipt", creditHold=false, autoPayEnabled=false,
     * invoiceDeliveryMethod="EMAIL".
     *
     * <p>
     * EXPECTED FAILURE (RED): billingRules is null; all jsonPath assertions fail.
     *
     * Issue: CAP-252
     */
    @Test
    @DisplayName("SS-003: snapshot billingRules defaults applied when no billing rules configured")
    void snapshotByParty_billingRules_defaultsApplied_whenNotConfigured() throws Exception {
        CommercialParty party = createTestParty();

        // Issue CAP-252: all assertions below fail RED because billingRules is null
        mockMvc.perform(get("/v1/crm/snapshot/party/{partyId}", party.getPartyId())
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", PARTY_VIEW_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billingRules.poRequired").value(false))
                .andExpect(jsonPath("$.billingRules.taxExempt").value(false))
                .andExpect(jsonPath("$.billingRules.paymentTerms").value("Due on Receipt"))
                .andExpect(jsonPath("$.billingRules.creditHold").value(false))
                .andExpect(jsonPath("$.billingRules.autoPayEnabled").value(false))
                .andExpect(jsonPath("$.billingRules.invoiceDeliveryMethod").value("EMAIL"));
    }

    // -------------------------------------------------------------------------
    // SS-004 — Second request returns snapshot with source=CACHE
    // -------------------------------------------------------------------------

    /**
     * SS-004: A second identical request for the same partyId must be served from
     * cache, and the snapshotMetadata.source field must equal "CACHE".
     *
     * <p>
     * EXPECTED FAILURE (RED): source is never populated (always null), so the
     * second response does not return source="CACHE".
     *
     * Issue: CAP-252
     */
    @Test
    @DisplayName("SS-004: second snapshot request for same partyId returns source=CACHE")
    void snapshotByParty_secondRequest_returnedFromCache() throws Exception {
        CommercialParty party = createTestParty();
        String url = "/v1/crm/snapshot/party/" + party.getPartyId();

        // First request — primes the cache (source may be null or CRM_API)
        mockMvc.perform(get(url).header("X-User", TEST_USER).header("X-Authorities", PARTY_VIEW_AUTH))
                .andExpect(status().isOk());

        // Issue CAP-252: second request must return source=CACHE — currently fails RED
        mockMvc.perform(get(url).header("X-User", TEST_USER).header("X-Authorities", PARTY_VIEW_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotMetadata.source").value("CACHE"));
    }

    // -------------------------------------------------------------------------
    // SS-006 — Unknown partyId returns 404
    // -------------------------------------------------------------------------

    /**
     * SS-006: GET for a partyId that does not exist returns HTTP 404.
     *
     * <p>
     * NOTE: This assertion is expected to PASS already (regression guard).
     *
     * Issue: CAP-252
     */
    @Test
    @DisplayName("SS-006: snapshot for unknown partyId returns 404")
    void snapshotByParty_unknownPartyId_returns404() throws Exception {
        UUID unknownId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        mockMvc.perform(get("/v1/crm/snapshot/party/{partyId}", unknownId)
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", PARTY_VIEW_AUTH))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // SS-007 — Unauthenticated request returns 401
    // -------------------------------------------------------------------------

    /**
     * SS-007: A request without any authentication must be rejected with HTTP 401.
     *
     * <p>
     * NOTE: If Spring Security is fully disabled in the test profile
     * ({@code security.enabled=false}), this test may return 200 instead of 401.
     * The assertion documents the required production contract regardless.
     *
     * Issue: CAP-252
     */
    @Test
    @DisplayName("SS-007: unauthenticated request to snapshot endpoint returns 401")
    void snapshotByParty_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get(
                        "/v1/crm/snapshot/party/{partyId}", UUID.fromString("00000000-0000-0000-0000-000000000001")))
                .andExpect(status().isUnauthorized());
    }
}

package com.positivity.inventory.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.internal.dto.replenishment.CreateReplenishmentPolicyRequest;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentNeedResponse;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentPolicyResponse;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentScanResultResponse;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentTaskResponse;
import com.positivity.inventory.internal.dto.replenishment.UpdateReplenishmentPolicyRequest;
import com.positivity.inventory.internal.exception.SnoozeUntilNotInFutureException;
import com.positivity.inventory.service.ReplenishmentService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Contract behavior integration tests for the Replenishment API (Story #30 —
 * Replenish Pick Faces from Backstock).
 *
 * <p>
 * Verifies the replenishment task and policy endpoints per ADR-0011 (gateway
 * security — X-User and X-Authorities headers required), ADR-0017 (HTTP
 * response codes: 200 for reads, 201 for resource creation, 400 for invalid
 * input), and ADR-0018 (actor fields populated from security context headers).
 *
 * <p>
 * Tests are intentionally RED: {@code ReplenishmentController} does not yet
 * exist, so all requests will receive 404 instead of the expected status codes.
 *
 * Issue: #30
 */
@DisplayName("Replenishment Contract Behavior")
class ReplenishmentContractBehaviorIT extends BaseContractIntegrationTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ReplenishmentService replenishmentService;

    // ─── AC1: GET /replenishment/tasks returns 200 with task list ─────────────

    /**
     * Verifies that a request for pending replenishment tasks returns 200 with the
     * first task's itemSKU present, per story #30 AC1.
     *
     * Issue: #30
     */
    @Test
    @DisplayName("AC1: GET /replenishment/tasks with pending tasks returns 200 with task list")
    void getReplenishmentTasks_withPendingTasks_returns200WithTaskList() throws Exception {
        // Issue #30: task list endpoint must return itemSKU for each queued task
        String taskId = UUID.fromString("00000000-0000-0000-0000-000000000001").toString();
        String itemSKU = "SKU-WIDGET-001";
        UUID sourceLocationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID destinationLocationId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        ReplenishmentTaskResponse task = ReplenishmentTaskResponse.builder()
                .taskId(taskId)
                .itemSKU(itemSKU)
                .quantity(10)
                .sourceLocationId(sourceLocationId)
                .destinationLocationId(destinationLocationId)
                .status("PENDING")
                .triggerType("MIN_LEVEL")
                .decisionReason("Stock below minimum threshold")
                .createdAt(Instant.now(TEST_CLOCK).toString())
                .build();

        when(replenishmentService.getReplenishmentTasks()).thenReturn(List.of(task));

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/replenishment/tasks")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].itemSKU").value(itemSKU));
    }

    // ─── AC2: GET /replenishment/tasks returns 200 with empty list ────────────

    /**
     * Verifies that when no replenishment tasks are queued the endpoint returns 200
     * with an empty JSON array rather than 404 or 204, per ADR-0017.
     *
     * Issue: #30
     */
    @Test
    @DisplayName("AC2: GET /replenishment/tasks with no pending tasks returns 200 with empty array")
    void getReplenishmentTasks_withNoTasks_returns200WithEmptyArray() throws Exception {
        // Issue #30: ADR-0017 — empty list resource must return 200, not 404/204
        when(replenishmentService.getReplenishmentTasks()).thenReturn(Collections.emptyList());

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/replenishment/tasks")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    // ─── AC3: GET /replenishment/policies returns 200 with policy list ────────

    /**
     * Verifies that the replenishment policies endpoint returns 200 with at least
     * one policy entry containing a locationId, per story #30 AC3.
     *
     * Issue: #30
     */
    @Test
    @DisplayName("AC3: GET /replenishment/policies returns 200 with policy list")
    void getReplenishmentPolicies_withExistingPolicies_returns200WithPolicyList() throws Exception {
        // Issue #30: policy list must include locationId for each configured policy
        String policyId =
                UUID.fromString("00000000-0000-0000-0000-000000000001").toString();
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        ReplenishmentPolicyResponse policy = ReplenishmentPolicyResponse.builder()
                .policyId(policyId)
                .locationId(locationId)
                .itemSKU("SKU-BOLT-M5")
                .minimumQuantity(20)
                .maximumQuantity(100)
                .createdAt(Instant.now(TEST_CLOCK).toString())
                .build();

        when(replenishmentService.getReplenishmentPolicies(
                        org.mockito.ArgumentMatchers.isNull(), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(policy)));

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/replenishment/policies")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].locationId").value(locationId.toString()));
    }

    // ─── AC4: POST /replenishment/policies returns 201 with created policy ────

    /**
     * Verifies that submitting a valid policy creation request returns 201 with the
     * newly created policy's non-null policyId, per story #30 AC4 and ADR-0017
     * (201 for resource creation).
     *
     * Issue: #30
     */
    @Test
    @DisplayName("AC4: POST /replenishment/policies with valid body returns 201 with created policy")
    void createReplenishmentPolicy_withValidRequest_returns201WithCreatedPolicy() throws Exception {
        // Issue #30: ADR-0017 — resource creation must return 201 with new resource ID
        // ADR-0018 — actor header X-User populated from gateway into response actor
        String policyId =
                UUID.fromString("00000000-0000-0000-0000-000000000001").toString();
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        ReplenishmentPolicyResponse created = ReplenishmentPolicyResponse.builder()
                .policyId(policyId)
                .locationId(locationId)
                .itemSKU("SKU-NUT-M5")
                .minimumQuantity(10)
                .maximumQuantity(50)
                .createdAt(Instant.now(TEST_CLOCK).toString())
                .build();

        when(replenishmentService.createReplenishmentPolicy(any(CreateReplenishmentPolicyRequest.class)))
                .thenReturn(created);

        String requestBody = buildCreatePolicyRequestBody(locationId.toString(), "SKU-NUT-M5", 10, 50);

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/replenishment/policies"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.policyId").isNotEmpty());
    }

    // ─── AC5: POST /replenishment/policies with invalid body returns 400 ──────

    /**
     * Verifies that a policy creation request with missing required fields returns
     * 400 Bad Request per ADR-0017 (input validation failure must yield 400, not
     * 422 or 500).
     *
     * Issue: #30
     */
    @Test
    @DisplayName("AC5: POST /replenishment/policies with missing required fields returns 400")
    void createReplenishmentPolicy_withMissingRequiredFields_returns400() throws Exception {
        // Issue #30: ADR-0017 — invalid/incomplete request body must return 400
        // Empty body with no locationId or itemSKU — required fields absent
        mockMvc.perform(withGatewayAuth(post("/v1/inventory/replenishment/policies"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ─── F1 (#1025): POST /replenishment/scan + inventory:replenishment:manage ─

    /**
     * Verifies that the manual batch scan endpoint returns 200 with the scan
     * summary when the caller holds {@code inventory:replenishment:manage}.
     *
     * Issue: #1025
     */
    @Test
    @DisplayName("F1: POST /replenishment/scan with manage permission returns 200 with scan summary")
    void runReplenishmentScan_withManagePermission_returns200WithSummary() throws Exception {
        when(replenishmentService.runBatchReplenishmentScan())
                .thenReturn(ReplenishmentScanResultResponse.builder()
                        .policiesEvaluated(4)
                        .policiesBelowMinimum(2)
                        .tasksCreated(1)
                        .tasksRefreshed(1)
                        .scanAt(Instant.now(TEST_CLOCK).toString())
                        .build());

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/replenishment/scan")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policiesEvaluated").value(4))
                .andExpect(jsonPath("$.tasksCreated").value(1));
    }

    /**
     * Verifies that the scan endpoint is denied (403) without
     * {@code inventory:replenishment:manage} — even for a caller holding the
     * legacy {@code inventory:adjustment:create} authority.
     *
     * Issue: #1025
     */
    @Test
    @DisplayName("F1: POST /replenishment/scan without manage permission returns 403")
    void runReplenishmentScan_withoutManagePermission_returns403() throws Exception {
        mockMvc.perform(withCreateOnlyAuth(post("/v1/inventory/replenishment/scan")))
                .andExpect(status().isForbidden());
    }

    /**
     * Verifies that policy creation migrated off {@code inventory:adjustment:create}
     * onto {@code inventory:replenishment:manage}: the legacy authority alone is
     * now rejected with 403.
     *
     * Issue: #1025
     */
    @Test
    @DisplayName("F1: POST /replenishment/policies with only legacy adjustment:create returns 403")
    void createReplenishmentPolicy_withLegacyAdjustmentCreateOnly_returns403() throws Exception {
        String requestBody = buildCreatePolicyRequestBody("00000000-0000-0000-0000-000000000001", "SKU-NUT-M5", 10, 50);

        mockMvc.perform(withCreateOnlyAuth(post("/v1/inventory/replenishment/policies"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isForbidden());
    }

    // ─── F3 (#1041): snooze / update / needs endpoints ───────────────────────

    /**
     * Verifies that snoozing a policy with a future instant returns 200 with the
     * updated snooze state when the caller holds
     * {@code inventory:replenishment:manage}.
     *
     * Issue: #1041
     */
    @Test
    @DisplayName("F3: POST /replenishment/policies/{id}/snooze with manage permission returns 200")
    void snoozeReplenishmentPolicy_withManagePermission_returns200() throws Exception {
        UUID policyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Instant snoozedUntil = Instant.parse("2024-02-01T00:00:00Z");
        when(replenishmentService.snoozeReplenishmentPolicy(any(UUID.class), any(Instant.class)))
                .thenReturn(ReplenishmentPolicyResponse.builder()
                        .policyId(policyId.toString())
                        .locationId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
                        .itemSKU("SKU-BOLT-M5")
                        .minimumQuantity(20)
                        .maximumQuantity(100)
                        .preferredSourceType("EITHER")
                        .snoozedUntil(snoozedUntil.toString())
                        .active(true)
                        .createdAt(Instant.now(TEST_CLOCK).toString())
                        .build());

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/replenishment/policies/" + policyId + "/snooze"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"snoozedUntil\":\"" + snoozedUntil + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snoozedUntil").value(snoozedUntil.toString()));
    }

    /**
     * Verifies that a non-future snooze instant is rejected with a deterministic
     * 422 carrying the {@code REPLENISHMENT_SNOOZE_NOT_IN_FUTURE} error code.
     *
     * Issue: #1041
     */
    @Test
    @DisplayName("F3: POST /replenishment/policies/{id}/snooze with past instant returns 422")
    void snoozeReplenishmentPolicy_withPastInstant_returns422() throws Exception {
        Instant past = Instant.parse("2020-01-01T00:00:00Z");
        when(replenishmentService.snoozeReplenishmentPolicy(any(UUID.class), any(Instant.class)))
                .thenThrow(new SnoozeUntilNotInFutureException(past));

        mockMvc.perform(withGatewayAuth(post(
                                "/v1/inventory/replenishment/policies/00000000-0000-0000-0000-000000000001/snooze"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"snoozedUntil\":\"" + past + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REPLENISHMENT_SNOOZE_NOT_IN_FUTURE"));
    }

    /**
     * Verifies that the snooze endpoint is denied (403) without
     * {@code inventory:replenishment:manage}.
     *
     * Issue: #1041
     */
    @Test
    @DisplayName("F3: POST /replenishment/policies/{id}/snooze without manage permission returns 403")
    void snoozeReplenishmentPolicy_withoutManagePermission_returns403() throws Exception {
        mockMvc.perform(withCreateOnlyAuth(post(
                                "/v1/inventory/replenishment/policies/00000000-0000-0000-0000-000000000001/snooze"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"snoozedUntil\":\"2024-02-01T00:00:00Z\"}"))
                .andExpect(status().isForbidden());
    }

    /**
     * Verifies that the full-replace policy update returns 200 with the updated
     * fields when the caller holds {@code inventory:replenishment:manage}.
     *
     * Issue: #1041
     */
    @Test
    @DisplayName("F3: PUT /replenishment/policies/{id} with manage permission returns 200")
    void updateReplenishmentPolicy_withManagePermission_returns200() throws Exception {
        UUID policyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(replenishmentService.updateReplenishmentPolicy(
                        any(UUID.class), any(UpdateReplenishmentPolicyRequest.class)))
                .thenReturn(ReplenishmentPolicyResponse.builder()
                        .policyId(policyId.toString())
                        .locationId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
                        .itemSKU("SKU-BOLT-M5")
                        .minimumQuantity(8)
                        .maximumQuantity(40)
                        .orderMultiple(6)
                        .preferredSourceType("PURCHASE")
                        .active(true)
                        .createdAt(Instant.now(TEST_CLOCK).toString())
                        .build());

        mockMvc.perform(withGatewayAuth(put("/v1/inventory/replenishment/policies/" + policyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minimumQuantity\":8,\"maximumQuantity\":40,\"orderMultiple\":6,"
                                + "\"preferredSourceType\":\"PURCHASE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderMultiple").value(6))
                .andExpect(jsonPath("$.preferredSourceType").value("PURCHASE"));
    }

    /**
     * Verifies that the policy update endpoint is denied (403) without
     * {@code inventory:replenishment:manage}.
     *
     * Issue: #1041
     */
    @Test
    @DisplayName("F3: PUT /replenishment/policies/{id} without manage permission returns 403")
    void updateReplenishmentPolicy_withoutManagePermission_returns403() throws Exception {
        mockMvc.perform(withCreateOnlyAuth(
                                put("/v1/inventory/replenishment/policies/00000000-0000-0000-0000-000000000001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minimumQuantity\":8,\"maximumQuantity\":40}"))
                .andExpect(status().isForbidden());
    }

    /**
     * Verifies that the side-effect-free needs report returns 200 with the per-policy
     * evaluation rows for a caller holding {@code inventory:on_hand:view}.
     *
     * Issue: #1041
     */
    @Test
    @DisplayName("F3/F6: GET /replenishment/needs returns 200 with needs rows")
    void getReplenishmentNeeds_returns200WithNeeds() throws Exception {
        when(replenishmentService.getReplenishmentNeeds())
                .thenReturn(List.of(ReplenishmentNeedResponse.builder()
                        .policyId("00000000-0000-0000-0000-000000000001")
                        .itemSKU("SKU-BOLT-M5")
                        .locationId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
                        .onHand(3)
                        .projectedAvailable(3)
                        .leadHorizonDate("2024-01-06")
                        .leadTimeSource("POLICY_OVERRIDE")
                        .minimumQuantity(5)
                        .maximumQuantity(10)
                        .wouldTrigger(true)
                        .suggestedQuantity(12)
                        .deadlineDate("2024-01-03")
                        .preferredSourceType("EITHER")
                        .build()));

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/replenishment/needs")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].wouldTrigger").value(true))
                .andExpect(jsonPath("$[0].suggestedQuantity").value(12))
                .andExpect(jsonPath("$[0].deadlineDate").value("2024-01-03"));
    }

    /**
     * Verifies that the needs report is denied (403) without
     * {@code inventory:on_hand:view}.
     *
     * Issue: #1041
     */
    @Test
    @DisplayName("F3/F6: GET /replenishment/needs without view permission returns 403")
    void getReplenishmentNeeds_withoutViewPermission_returns403() throws Exception {
        mockMvc.perform(withCreateOnlyAuth(get("/v1/inventory/replenishment/needs")))
                .andExpect(status().isForbidden());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Builds a JSON request body for creating a replenishment policy.
     *
     * @param locationId      the pick-face location the policy applies to
     * @param itemSKU         the SKU code governed by this policy
     * @param minimumQuantity replenishment trigger threshold
     * @param maximumQuantity target fill level after replenishment
     * @return serialized JSON request body
     */
    private String buildCreatePolicyRequestBody(
            String locationId, String itemSKU, int minimumQuantity, int maximumQuantity) throws Exception {
        var node = objectMapper.createObjectNode();
        node.put("locationId", locationId);
        node.put("itemSKU", itemSKU);
        node.put("minimumQuantity", minimumQuantity);
        node.put("maximumQuantity", maximumQuantity);
        return objectMapper.writeValueAsString(node);
    }
}

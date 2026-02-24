package com.positivity.inventory.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.positivity.inventory.internal.dto.replenishment.CreateReplenishmentPolicyRequest;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentPolicyResponse;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentTaskResponse;
import com.positivity.inventory.service.ReplenishmentService;
import com.positivity.inventory.service.contract.BaseContractIntegrationTest;

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
        String taskId = UUID.randomUUID().toString();
        String itemSKU = "SKU-WIDGET-001";
        String sourceLocationId = UUID.randomUUID().toString();
        String destinationLocationId = UUID.randomUUID().toString();

        ReplenishmentTaskResponse task = ReplenishmentTaskResponse.builder()
                .taskId(taskId)
                .itemSKU(itemSKU)
                .quantity(10)
                .sourceLocationId(sourceLocationId)
                .destinationLocationId(destinationLocationId)
                .status("PENDING")
                .triggerType("MIN_LEVEL")
                .decisionReason("Stock below minimum threshold")
                .createdAt(Instant.now().toString())
                .build();

        when(replenishmentService.getReplenishmentTasks()).thenReturn(List.of(task));

        mockMvc.perform(withGatewayAuth(get("/api/v1/inventory/replenishment/tasks")))
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

        mockMvc.perform(withGatewayAuth(get("/api/v1/inventory/replenishment/tasks")))
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
        String policyId = UUID.randomUUID().toString();
        String locationId = UUID.randomUUID().toString();

        ReplenishmentPolicyResponse policy = ReplenishmentPolicyResponse.builder()
                .policyId(policyId)
                .locationId(locationId)
                .itemSKU("SKU-BOLT-M5")
                .minimumQuantity(20)
                .maximumQuantity(100)
                .createdAt(Instant.now().toString())
                .build();

        when(replenishmentService.getReplenishmentPolicies()).thenReturn(List.of(policy));

        mockMvc.perform(withGatewayAuth(get("/api/v1/inventory/replenishment/policies")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].locationId").value(locationId));
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
        String policyId = UUID.randomUUID().toString();
        String locationId = UUID.randomUUID().toString();

        ReplenishmentPolicyResponse created = ReplenishmentPolicyResponse.builder()
                .policyId(policyId)
                .locationId(locationId)
                .itemSKU("SKU-NUT-M5")
                .minimumQuantity(10)
                .maximumQuantity(50)
                .createdAt(Instant.now().toString())
                .build();

        when(replenishmentService.createReplenishmentPolicy(any(CreateReplenishmentPolicyRequest.class)))
                .thenReturn(created);

        String requestBody = buildCreatePolicyRequestBody(locationId, "SKU-NUT-M5", 10, 50);

        mockMvc.perform(withGatewayAuth(post("/api/v1/inventory/replenishment/policies"))
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
        mockMvc.perform(withGatewayAuth(post("/api/v1/inventory/replenishment/policies"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest());
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
            String locationId,
            String itemSKU,
            int minimumQuantity,
            int maximumQuantity) throws Exception {
        var node = objectMapper.createObjectNode();
        node.put("locationId", locationId);
        node.put("itemSKU", itemSKU);
        node.put("minimumQuantity", minimumQuantity);
        node.put("maximumQuantity", maximumQuantity);
        return objectMapper.writeValueAsString(node);
    }
}

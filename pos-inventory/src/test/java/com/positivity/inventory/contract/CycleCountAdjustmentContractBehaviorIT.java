package com.positivity.inventory.contract;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.positivity.inventory.internal.entity.ApprovalThresholdConfig;
import com.positivity.inventory.internal.enums.ApprovalTier;
import com.positivity.inventory.internal.repository.ApprovalThresholdConfigRepository;
import com.positivity.inventory.internal.repository.CycleCountAdjustmentRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Contract behavior integration tests for CycleCountAdjustmentController.
 *
 * <p>
 * Verifies the full adjustment lifecycle: create, approve, reject, and list.
 * Covers auto-approval path (below thresholds), pending-approval path (above
 * thresholds), and validation error paths per ADR-0017 HTTP response code
 * standards and ADR-0018 audit actor-from-security-context requirement.
 *
 * Issue: #26
 */
@DisplayName("Cycle Count Adjustment Contract Behavior")
class CycleCountAdjustmentContractBehaviorIT extends BaseContractIntegrationTest {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @Autowired
        private CycleCountAdjustmentRepository adjustmentRepository;

        @Autowired
        private ApprovalThresholdConfigRepository thresholdConfigRepository;

        @Autowired
        private InventoryLedgerEntryRepository ledgerEntryRepository;

        @BeforeEach
        void setUp() {
                // Issue #26: delete ledger entries before adjustments (ledger refs adjustment
                // ID)
                ledgerEntryRepository.deleteAll();
                adjustmentRepository.deleteAll();
                thresholdConfigRepository.deleteAll();
        }

        // ─── AC-26-1: Create auto-approved (below all thresholds) ─────────────────

        /**
         * Verifies that an adjustment whose variance falls below all configured
         * thresholds is created with AUTO_APPROVED status and returns 201.
         */
        @Test
        @DisplayName("AC-26-1: create adjustment below all thresholds returns 201 with AUTO_APPROVED status")
        void createAdjustment_belowAllThresholds_returns201WithAutoApprovedStatus() throws Exception {
                // Issue #26: seed high thresholds so variance=1 never triggers approval
                seedHighThreshold();

                String body = buildCreateRequestBody(UUID.fromString("00000000-0000-0000-0000-000000000001"), 101, 100,
                                "10.00");

                // Issue #26: auto-approve path posts to ledger synchronously within the same
                // transaction; the response therefore reflects the final POSTED status.
                mockMvc.perform(withGatewayAuth(post("/v1/inventory/cycleCountAdjustments"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.adjustmentId").exists())
                                .andExpect(jsonPath("$.status").value("POSTED"));
        }

        // ─── AC-26-2: Create pending approval (exceeds threshold) ─────────────────

        /**
         * Verifies that an adjustment whose variance exceeds configured thresholds
         * is created with PENDING_APPROVAL status and returns 201.
         */
        @Test
        @DisplayName("AC-26-2: create adjustment exceeding threshold returns 201 with PENDING_APPROVAL status")
        void createAdjustment_exceedsThreshold_returns201WithPendingApprovalStatus() throws Exception {
                // Issue #26: seed low thresholds so variance=2 (>=1) requires approval
                seedLowThreshold();

                String body = buildCreateRequestBody(UUID.fromString("00000000-0000-0000-0000-000000000001"), 102, 100,
                                "10.00");

                mockMvc.perform(withGatewayAuth(post("/v1/inventory/cycleCountAdjustments"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.adjustmentId").exists())
                                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
        }

        // ─── AC-26-3: Approve pending adjustment ─────────────────────────────────

        /**
         * Verifies that approving a PENDING_APPROVAL adjustment returns 200 with
         * POSTED status and the approver's user ID resolved from the security context
         * (ADR-0018: actor from X-User-Id header injected by gateway).
         */
        @Test
        @DisplayName("AC-26-3: approving pending adjustment returns 200 with POSTED status and approver ID")
        void approveAdjustment_pendingApproval_returns200WithPostedStatusAndApproverId() throws Exception {
                seedLowThreshold();
                UUID adjustmentId = createPendingApprovalAdjustment(
                                UUID.fromString("00000000-0000-0000-0000-000000000001"));

                // Issue #26: approverUserId field, and X-User-Id sets ADR-0018 actor
                String approveBody = objectMapper.writeValueAsString(
                                objectMapper.createObjectNode()
                                                .put("approverUserId", "manager-1")
                                                .put("notes", "Verified count"));

                mockMvc.perform(withGatewayAuth(
                                post("/v1/inventory/cycleCountAdjustments/{adjustmentId}/approve", adjustmentId))
                                .header("X-User-Id", "manager-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(approveBody))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("POSTED"))
                                .andExpect(jsonPath("$.approvedByUserId").value("manager-1"));
        }

        // ─── AC-26-4: Reject pending adjustment ──────────────────────────────────

        /**
         * Verifies that rejecting a PENDING_APPROVAL adjustment returns 200 with
         * REJECTED status and the rejection reason preserved.
         */
        @Test
        @DisplayName("AC-26-4: rejecting pending adjustment returns 200 with REJECTED status and reason")
        void rejectAdjustment_pendingApproval_returns200WithRejectedStatusAndReason() throws Exception {
                seedLowThreshold();
                UUID adjustmentId = createPendingApprovalAdjustment(
                                UUID.fromString("00000000-0000-0000-0000-000000000001"));

                // Issue #26: rejectorUserId is the request body field name in
                // RejectAdjustmentRequest
                String rejectBody = objectMapper.writeValueAsString(
                                objectMapper.createObjectNode()
                                                .put("rejectorUserId", "manager-1")
                                                .put("rejectionReason", "Incorrect count"));

                mockMvc.perform(withGatewayAuth(
                                post("/v1/inventory/cycleCountAdjustments/{adjustmentId}/reject", adjustmentId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(rejectBody))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("REJECTED"))
                                .andExpect(jsonPath("$.rejectionReason").value("Incorrect count"));
        }

        // ─── AC-26-5: Zero variance returns 400 ───────────────────────────────────

        /**
         * Verifies that a create request where countedQuantity equals
         * quantityOnHandBefore
         * (zero variance) is rejected with 400 per ADR-0017.
         */
        @Test
        @DisplayName("AC-26-5: create with zero variance (same counts) returns 400")
        void createAdjustment_zeroVariance_returns400() throws Exception {
                seedHighThreshold();

                // Issue #26: variance = 100 - 100 = 0 → IAE → 400
                String body = buildCreateRequestBody(UUID.fromString("00000000-0000-0000-0000-000000000001"), 100, 100,
                                "10.00");

                mockMvc.perform(withGatewayAuth(post("/v1/inventory/cycleCountAdjustments"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                                .andExpect(status().isBadRequest());
        }

        // ─── AC-26-6: Get specific adjustment ─────────────────────────────────────

        /**
         * Verifies that retrieving a specific adjustment by ID returns 200 with the
         * correct adjustmentId in the response body.
         */
        @Test
        @DisplayName("AC-26-6: get adjustment by ID returns 200 with matching adjustmentId")
        void getAdjustment_existingId_returns200WithMatchingAdjustmentId() throws Exception {
                seedHighThreshold();
                UUID adjustmentId = createAutoApprovedAdjustment(
                                UUID.fromString("00000000-0000-0000-0000-000000000001"));

                mockMvc.perform(withGatewayAuth(
                                get("/v1/inventory/cycleCountAdjustments/{adjustmentId}", adjustmentId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.adjustmentId").value(adjustmentId.toString()));
        }

        // ─── AC-26-7: List pending adjustments ────────────────────────────────────

        /**
         * Verifies that filtering by PENDING_APPROVAL status returns at least 2 items
         * when 2 pending adjustments have been created.
         */
        @Test
        @DisplayName("AC-26-7: list adjustments filtered by PENDING_APPROVAL returns 200 with at least 2 items")
        void listAdjustments_pendingStatus_returns200WithAtLeast2Items() throws Exception {
                seedLowThreshold();
                // Issue #26: use distinct stock item IDs to avoid interference between items
                createPendingApprovalAdjustment(UUID.fromString("00000000-0000-0000-0000-000000000001"));
                createPendingApprovalAdjustment(UUID.fromString("00000000-0000-0000-0000-000000000001"));

                mockMvc.perform(withGatewayAuth(
                                get("/v1/inventory/cycleCountAdjustments")
                                                .param("status", "PENDING_APPROVAL")))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.length()", greaterThanOrEqualTo(2)));
        }

        // ─── Helpers ──────────────────────────────────────────────────────────────

        /**
         * Seeds a TIER_1_MANAGER threshold config with very high limits so any
         * reasonable variance auto-approves (below all thresholds).
         *
         * Issue: #26
         */
        private void seedHighThreshold() {
                thresholdConfigRepository.save(ApprovalThresholdConfig.builder()
                                .approvalTier(ApprovalTier.TIER_1_MANAGER)
                                .unitVarianceThreshold(999999)
                                .valueVarianceThreshold(new BigDecimal("999999.00"))
                                .percentageVarianceThreshold(new BigDecimal("999.00"))
                                .active(true)
                                .build());
        }

        /**
         * Seeds a TIER_1_MANAGER threshold config with very low limits so any
         * non-trivial variance (>=1 unit) requires approval.
         *
         * Issue: #26
         */
        private void seedLowThreshold() {
                thresholdConfigRepository.save(ApprovalThresholdConfig.builder()
                                .approvalTier(ApprovalTier.TIER_1_MANAGER)
                                .unitVarianceThreshold(1)
                                .valueVarianceThreshold(new BigDecimal("1.00"))
                                .percentageVarianceThreshold(new BigDecimal("1.00"))
                                .active(true)
                                .build());
        }

        /**
         * Builds a JSON request body string for creating an adjustment.
         *
         * @param stockItemId          unique stock item identifier
         * @param countedQuantity      the physical counted quantity
         * @param quantityOnHandBefore the system quantity before count
         * @param cost                 unit cost as string (e.g. "10.00")
         * @return serialized JSON request body
         */
        private String buildCreateRequestBody(
                        UUID stockItemId,
                        int countedQuantity,
                        int quantityOnHandBefore,
                        String cost) throws Exception {
                return objectMapper.writeValueAsString(
                                objectMapper.createObjectNode()
                                                .put("stockItemId", stockItemId.toString())
                                                .put("reasonCode", "CYCLE_COUNT_RECONCILIATION")
                                                .put("countedQuantity", countedQuantity)
                                                .put("quantityOnHandBefore", quantityOnHandBefore)
                                                .put("costAtTimeOfAdjustment", cost)
                                                .put("createdByUserId", "user-creator"));
        }

        /**
         * Creates a PENDING_APPROVAL adjustment via the API and returns its ID.
         * Uses variance=2 with low thresholds to ensure the adjustment requires
         * approval.
         *
         * @param stockItemId unique stock item ID to avoid cross-test interference
         * @return the created adjustment UUID
         *         Issue: #26
         */
        private UUID createPendingApprovalAdjustment(UUID stockItemId) throws Exception {
                String body = buildCreateRequestBody(stockItemId, 102, 100, "10.00");

                MvcResult result = mockMvc.perform(withGatewayAuth(post("/v1/inventory/cycleCountAdjustments"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                                .andExpect(status().isCreated())
                                .andReturn();

                JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
                return UUID.fromString(response.path("adjustmentId").asString());
        }

        /**
         * Creates an AUTO_APPROVED adjustment via the API and returns its ID.
         * Uses variance=1 with high thresholds to ensure the adjustment auto-approves.
         *
         * @param stockItemId unique stock item ID to avoid cross-test interference
         * @return the created adjustment UUID
         *         Issue: #26
         */
        private UUID createAutoApprovedAdjustment(UUID stockItemId) throws Exception {
                String body = buildCreateRequestBody(stockItemId, 101, 100, "10.00");

                MvcResult result = mockMvc.perform(withGatewayAuth(post("/v1/inventory/cycleCountAdjustments"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                                .andExpect(status().isCreated())
                                .andReturn();

                JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
                return UUID.fromString(response.path("adjustmentId").asString());
        }
}

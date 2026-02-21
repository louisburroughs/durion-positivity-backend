package com.positivity.inventory.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

import com.positivity.inventory.internal.event.InventoryAuditEvent;
import com.positivity.inventory.internal.model.cyclecount.ApprovalThresholdConfig;
import com.positivity.inventory.internal.model.cyclecount.ApprovalTier;
import com.positivity.inventory.internal.repository.ApprovalThresholdConfigRepository;
import com.positivity.inventory.service.contract.BaseContractIntegrationTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Contract behavior tests for immutable audit events emitted by inventory
 * adjustment approval flows.
 *
 * Issue: #22
 */
@RecordApplicationEvents
@DisplayName("Inventory Audit Event Contract Behavior")
class InventoryAuditEventContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationEvents applicationEvents;

    @Autowired
    private ApprovalThresholdConfigRepository approvalThresholdConfigRepository;

    @BeforeEach
    void setUp() {
        approvalThresholdConfigRepository.deleteAll();
        approvalThresholdConfigRepository.save(ApprovalThresholdConfig.builder()
                .approvalTier(ApprovalTier.TIER_1_MANAGER)
                .unitVarianceThreshold(1)
                .valueVarianceThreshold(new BigDecimal("1.00"))
                .percentageVarianceThreshold(new BigDecimal("1.00"))
                .active(true)
                .build());
    }

    @Test
    @DisplayName("AC-22-1: approving cycle count adjustment publishes MovementAdjusted to movements topic")
    void approvingCycleCountAdjustment_publishesMovementAdjustedEventToMovementsTopic() throws Exception {
        Long adjustmentId = createPendingApprovalAdjustment();

        approveAdjustment(adjustmentId);

        // Issue #22: enforce MovementAdjusted event type and topic contract.
        JsonNode eventEnvelope = findApproveOperationEventEnvelope();
        assertThat(eventEnvelope.has("eventType")).isTrue();
        assertThat(eventEnvelope.get("eventType").asString()).isEqualTo("MovementAdjusted");
        assertThat(eventEnvelope.has("topic")).isTrue();
        assertThat(eventEnvelope.get("topic").asString()).isEqualTo("inventory.v1.movements");
    }

    @Test
    @DisplayName("AC-22-2: MovementAdjusted event contains required envelope fields and UUID eventId")
    void movementAdjustedEvent_containsRequiredEnvelopeAndUuidEventId() throws Exception {
        Long adjustmentId = createPendingApprovalAdjustment();

        approveAdjustment(adjustmentId);

        // Issue #22: enforce required immutable audit envelope fields.
        JsonNode eventEnvelope = findApproveOperationEventEnvelope();
        assertThat(eventEnvelope.has("schemaVersion")).isTrue();
        assertThat(eventEnvelope.has("eventId")).isTrue();
        assertThat(eventEnvelope.has("eventType")).isTrue();
        assertThat(eventEnvelope.has("occurredAt")).isTrue();
        assertThat(eventEnvelope.has("emittedAt")).isTrue();
        assertThat(eventEnvelope.has("sourceSystem")).isTrue();
        assertThat(eventEnvelope.has("tenantId")).isTrue();
        assertThat(eventEnvelope.has("actor")).isTrue();
        assertThat(eventEnvelope.has("correlationId")).isTrue();
        assertThat(eventEnvelope.has("aggregate")).isTrue();
        assertThat(eventEnvelope.has("payload")).isTrue();

        assertThatCode(() -> UUID.fromString(eventEnvelope.get("eventId").asString())).doesNotThrowAnyException();
    }

    private Long createPendingApprovalAdjustment() throws Exception {
        JsonNode createRequest = objectMapper.createObjectNode()
                .put("stockItemId", "SKU-ISSUE-22")
                .put("reasonCode", "CYCLE_COUNT_RECONCILIATION")
                .put("countedQuantity", 22)
                .put("quantityOnHandBefore", 10)
                .put("costAtTimeOfAdjustment", 12.50)
                .put("createdByUserId", "user-creator");

        String createResponse = mockMvc.perform(withGatewayAuth(post("/api/v1/inventory/cycleCountAdjustments"))
                .header("X-User", "user-creator")
                .header("X-User-Id", "user-creator")
                .header("X-Authorities", "inventory:adjustment:create,inventory:adjustment:approve")
                .header("X-Correlation-Id", "corr-issue-22-create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(createResponse).path("adjustmentId").asLong();
    }

    private void approveAdjustment(@NonNull Long adjustmentId) throws Exception {
        JsonNode approveRequest = objectMapper.createObjectNode()
                .put("approverUserId", "user-approver")
                .put("notes", "approve adjustment for audit event contract test");

        mockMvc.perform(
                withGatewayAuth(post("/api/v1/inventory/cycleCountAdjustments/{adjustmentId}/approve", adjustmentId))
                        .header("X-User", "user-approver")
                        .header("X-User-Id", "user-approver")
                        .header("X-Authorities", "inventory:adjustment:approve")
                        .header("X-Correlation-Id", "corr-issue-22-approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(approveRequest)))
                .andExpect(status().isOk());
    }

    private @NonNull JsonNode findApproveOperationEventEnvelope() {
        List<InventoryAuditEvent> approveEvents = applicationEvents.stream(InventoryAuditEvent.class)
                .filter(event -> "MovementAdjusted".equals(event.eventType()))
                .toList();

        assertThat(approveEvents).isNotEmpty();

        return objectMapper.valueToTree(approveEvents.get(approveEvents.size() - 1));
    }
}

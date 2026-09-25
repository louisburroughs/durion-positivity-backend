package com.positivity.inventory.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.domainevents.inventory.InventoryAdjustedV1;
import com.positivity.domainevents.inventory.InventoryAvailabilityUpdatedV1;
import com.positivity.domainevents.inventory.StorageLocationOnHandUpdatedV1;
import com.positivity.inventory.internal.config.OutboxEventWriter;
import com.positivity.inventory.internal.entity.ApprovalThresholdConfig;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.OutboxEvent;
import com.positivity.inventory.internal.enums.ApprovalTier;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.repository.ApprovalThresholdConfigRepository;
import com.positivity.inventory.internal.repository.CycleCountAdjustmentRepository;
import com.positivity.inventory.internal.repository.InventoryAdjustmentRequestRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.repository.OutboxEventRepository;
import com.positivity.inventory.internal.service.LedgerPostingService;
import com.positivity.tenancy.TenantResolver;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Contract behavior ITs for the {@code inventory.adjustment.posted} fact (odoo-parity J3, issue
 * #2190), mirroring {@link ScrapContractBehaviorIT}: a posted cycle-count adjustment (auto-approved
 * or approved) and an approved manual adjustment request each leave exactly one {@link
 * InventoryAdjustedV1} row in {@code event_outbox}, carrying the cost stamped on the posted ledger
 * entry, the signed quantity and the entry id; a rejection leaves none.
 */
@DisplayName("Inventory Adjustment Fact Contract Behavior")
class InventoryAdjustmentContractBehaviorIT extends BaseContractIntegrationTest {

    @TestConfiguration
    static class OutboxTestConfig {
        @Bean
        OutboxEventWriter outboxEventWriter(
                Clock clock,
                ObjectMapper objectMapper,
                OutboxEventRepository outboxEventRepository,
                TenantResolver tenantResolver) {
            return new OutboxEventWriter(clock, objectMapper, outboxEventRepository, tenantResolver);
        }
    }

    private static final UUID LOCATION_ID = UUID.fromString("01960004-0001-7000-8000-000000002190");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CycleCountAdjustmentRepository adjustmentRepository;

    @Autowired
    private InventoryAdjustmentRequestRepository adjustmentRequestRepository;

    @Autowired
    private ApprovalThresholdConfigRepository thresholdConfigRepository;

    @Autowired
    private InventoryLedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private InventoryStockSummaryRepository stockSummaryRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        stockSummaryRepository.deleteAll();
        adjustmentRepository.deleteAll();
        adjustmentRequestRepository.deleteAll();
        thresholdConfigRepository.deleteAll();
    }

    @Test
    @DisplayName("an auto-approved count loss posts COUNT_VARIANCE_OUT and queues one fact with the stamped cost")
    void autoApprovedCountLoss_queuesOneFact() throws Exception {
        seedThreshold(999999, "999999.00", "999.00");
        String sku = uniqueSku();
        seedReceipt(sku, new BigDecimal("10"), new BigDecimal("3.0000"));

        MvcResult result = mockMvc.perform(withGatewayAuth(post("/v1/inventory/cycleCountAdjustments"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(countBody(sku, 7, 10)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andReturn();
        UUID adjustmentId = UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("adjustmentId")
                .asString());

        InventoryLedgerEntry posted =
                ledgerEntryRepository.findByAdjustmentId(adjustmentId).orElseThrow();
        assertThat(posted.getEventType()).isEqualTo(InventoryLedgerEventType.COUNT_VARIANCE_OUT);

        JsonNode fact = singleFact(adjustmentId);
        assertThat(fact.get("adjustmentKind").asString()).isEqualTo(InventoryAdjustedV1.KIND_CYCLE_COUNT);
        assertThat(fact.get("ledgerEventType").asString()).isEqualTo("COUNT_VARIANCE_OUT");
        assertThat(fact.get("ledgerEntryId").asString())
                .isEqualTo(posted.getLedgerEntryId().toString());
        assertThat(fact.get("quantityDelta").decimalValue()).isEqualByComparingTo("-3");
        assertThat(fact.get("unitCost").decimalValue()).isEqualByComparingTo(posted.getUnitCost());
        assertThat(fact.get("costSource").asString()).isEqualTo("AVERAGE");
        assertThat(fact.get("locationId").asString()).isEqualTo(LOCATION_ID.toString());

        assertThat(outboxEventRepository.findAll())
                .anyMatch(row -> row.getPayload().contains(InventoryAvailabilityUpdatedV1.EVENT_TYPE));
        assertThat(outboxEventRepository.findAll())
                .anyMatch(row -> row.getPayload().contains(StorageLocationOnHandUpdatedV1.EVENT_TYPE));
    }

    @Test
    @DisplayName("an approved count gain queues one fact with a positive delta; nothing is queued while pending")
    void approvedCountGain_queuesOneFact() throws Exception {
        seedThreshold(1, "1.00", "1.00");
        String sku = uniqueSku();
        seedReceipt(sku, new BigDecimal("10"), new BigDecimal("3.0000"));

        MvcResult created = mockMvc.perform(withGatewayAuth(post("/v1/inventory/cycleCountAdjustments"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(countBody(sku, 14, 10)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andReturn();
        UUID adjustmentId = UUID.fromString(objectMapper
                .readTree(created.getResponse().getContentAsString())
                .get("adjustmentId")
                .asString());
        assertThat(factRows(adjustmentId)).isEmpty();

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/cycleCountAdjustments/{id}/approve", adjustmentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"));

        InventoryLedgerEntry posted =
                ledgerEntryRepository.findByAdjustmentId(adjustmentId).orElseThrow();
        JsonNode fact = singleFact(adjustmentId);
        assertThat(fact.get("ledgerEventType").asString()).isEqualTo("COUNT_VARIANCE_IN");
        assertThat(fact.get("quantityDelta").decimalValue()).isEqualByComparingTo("4");
        assertThat(fact.get("ledgerEntryId").asString())
                .isEqualTo(posted.getLedgerEntryId().toString());
        assertThat(fact.get("unitCost").decimalValue()).isEqualByComparingTo(posted.getUnitCost());
        assertThat(fact.get("unitCost").decimalValue()).isEqualByComparingTo("3.0000");
    }

    @Test
    @DisplayName("a rejected count adjustment queues no fact")
    void rejectedCountAdjustment_queuesNoFact() throws Exception {
        seedThreshold(1, "1.00", "1.00");
        String sku = uniqueSku();
        seedReceipt(sku, new BigDecimal("10"), new BigDecimal("3.0000"));

        MvcResult created = mockMvc.perform(withGatewayAuth(post("/v1/inventory/cycleCountAdjustments"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(countBody(sku, 6, 10)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andReturn();
        String adjustmentId = objectMapper
                .readTree(created.getResponse().getContentAsString())
                .get("adjustmentId")
                .asString();

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/cycleCountAdjustments/{id}/reject", adjustmentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rejectorUserId\":\"manager-1\",\"rejectionReason\":\"recount\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        assertThat(outboxEventRepository.findAll())
                .noneMatch(row -> row.getPayload().contains(InventoryAdjustedV1.EVENT_TYPE));
    }

    @Test
    @DisplayName("an approved manual adjustment request queues one MANUAL_ADJUSTMENT fact")
    void approvedManualAdjustment_queuesOneFact() throws Exception {
        String sku = uniqueSku();
        seedReceipt(sku, new BigDecimal("10"), new BigDecimal("2.5000"));

        MvcResult created = mockMvc.perform(withGatewayAuth(post("/v1/inventory/adjustments"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productSku\":\"" + sku + "\",\"locationId\":\"" + LOCATION_ID + "\","
                                + "\"quantity\":-2,\"reasonCode\":\"DAMAGE\",\"unitOfMeasure\":\"EACH\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID requestId = UUID.fromString(objectMapper
                .readTree(created.getResponse().getContentAsString())
                .get("adjustmentRequestId")
                .asString());
        assertThat(factRows(requestId)).isEmpty();

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/adjustments/{id}/approve", requestId)))
                .andExpect(status().isOk());

        InventoryLedgerEntry posted =
                ledgerEntryRepository.findByAdjustmentId(requestId).orElseThrow();
        JsonNode fact = singleFact(requestId);
        assertThat(fact.get("adjustmentKind").asString()).isEqualTo(InventoryAdjustedV1.KIND_MANUAL_ADJUSTMENT);
        assertThat(fact.get("ledgerEventType").asString()).isEqualTo("ADJUSTMENT_OUT");
        assertThat(fact.get("ledgerEntryId").asString())
                .isEqualTo(posted.getLedgerEntryId().toString());
        assertThat(fact.get("quantityDelta").decimalValue()).isEqualByComparingTo("-2");
        assertThat(fact.get("unitCost").decimalValue()).isEqualByComparingTo(posted.getUnitCost());
        assertThat(fact.get("unitCost").decimalValue()).isEqualByComparingTo("2.5000");
        assertThat(fact.get("costSource").asString()).isEqualTo("AVERAGE");
        assertThat(fact.get("reasonCode").asString()).isEqualTo("DAMAGE");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private JsonNode singleFact(UUID adjustmentId) throws Exception {
        List<OutboxEvent> rows = factRows(adjustmentId);
        assertThat(rows).hasSize(1);
        JsonNode envelope = objectMapper.readTree(rows.getFirst().getPayload());
        assertThat(envelope.get("eventType").asString()).isEqualTo(InventoryAdjustedV1.EVENT_TYPE);
        assertThat(envelope.get("schemaVersion").asInt()).isEqualTo(InventoryAdjustedV1.SCHEMA_VERSION);
        assertThat(envelope.get("aggregateId").asString()).isEqualTo(adjustmentId.toString());
        return envelope.get("payload");
    }

    private List<OutboxEvent> factRows(UUID adjustmentId) {
        return outboxEventRepository.findAll().stream()
                .filter(row -> row.getPayload().contains(InventoryAdjustedV1.EVENT_TYPE)
                        && row.getPayload().contains(adjustmentId.toString()))
                .toList();
    }

    private void seedThreshold(int units, String value, String percentage) {
        thresholdConfigRepository.save(ApprovalThresholdConfig.builder()
                .approvalTier(ApprovalTier.TIER_1_MANAGER)
                .unitVarianceThreshold(units)
                .valueVarianceThreshold(new BigDecimal(value))
                .percentageVarianceThreshold(new BigDecimal(percentage))
                .active(true)
                .build());
    }

    private void seedReceipt(String sku, BigDecimal quantity, BigDecimal unitCost) {
        ledgerPostingService.post(InventoryLedgerEntry.builder()
                .stockItemId(sku)
                .eventType(InventoryLedgerEventType.GOODS_RECEIPT)
                .changeInQuantity(quantity)
                .quantityAfter(quantity)
                .unitCost(unitCost)
                .locationId(LOCATION_ID)
                .transactionUserId("seed")
                .build());
    }

    private String countBody(String sku, int counted, int onHandBefore) throws Exception {
        return objectMapper.writeValueAsString(objectMapper
                .createObjectNode()
                .put("stockItemId", sku)
                .put("locationId", LOCATION_ID.toString())
                .put("reasonCode", "CYCLE_COUNT_RECONCILIATION")
                .put("countedQuantity", counted)
                .put("quantityOnHandBefore", onHandBefore)
                // Client-supplied fallback only; the engine's running cost wins at create, and the
                // fact never carries this value.
                .put("costAtTimeOfAdjustment", "99.00")
                .put("createdByUserId", "user-creator"));
    }

    private String uniqueSku() {
        return "ADJ-IT-" + UUID.randomUUID().toString().substring(0, 8);
    }
}

package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import com.positivity.domainevents.inventory.InventoryAdjustedV1;
import com.positivity.domainevents.inventory.InventoryAvailabilityUpdatedV1;
import com.positivity.domainevents.inventory.StorageLocationOnHandUpdatedV1;
import com.positivity.inventory.internal.config.OutboxEventWriter;
import com.positivity.inventory.internal.cyclecount.service.CycleCountAdjustmentService;
import com.positivity.inventory.internal.cyclecount.service.CycleCountService;
import com.positivity.inventory.internal.dto.AdjustmentRequestResponse;
import com.positivity.inventory.internal.dto.CreateAdjustmentRequestDto;
import com.positivity.inventory.internal.dto.cyclecount.AdjustmentResponse;
import com.positivity.inventory.internal.dto.cyclecount.ApproveAdjustmentRequest;
import com.positivity.inventory.internal.dto.cyclecount.CountResponse;
import com.positivity.inventory.internal.dto.cyclecount.CreateAdjustmentRequest;
import com.positivity.inventory.internal.dto.cyclecount.RejectAdjustmentRequest;
import com.positivity.inventory.internal.dto.cyclecount.SubmitCountRequest;
import com.positivity.inventory.internal.entity.ApprovalThresholdConfig;
import com.positivity.inventory.internal.entity.CycleCountTask;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.OutboxEvent;
import com.positivity.inventory.internal.enums.AdjustmentStatus;
import com.positivity.inventory.internal.enums.ApprovalFlowType;
import com.positivity.inventory.internal.enums.ApprovalTier;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.TaskStatus;
import com.positivity.inventory.internal.exception.AdjustmentLedgerPostingException;
import com.positivity.inventory.internal.movement.service.StockMovementService;
import com.positivity.inventory.internal.repository.ApprovalThresholdConfigRepository;
import com.positivity.inventory.internal.repository.CycleCountAdjustmentRepository;
import com.positivity.inventory.internal.repository.CycleCountTaskRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.OutboxEventRepository;
import com.positivity.inventory.internal.repository.SkuCostStateRepository;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.LocationScope;
import com.positivity.tenancy.TenantResolver;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Spring-backed producer test for {@code inventory.adjustment.posted} (odoo-parity J3, issue #2190).
 *
 * <p>Drives the real services in real transactions with an outbox writer present, so a fact reaches
 * {@code event_outbox} only if the approving transaction commits: once per successful posting, never
 * for a rejection, a zero recomputed variance or a posting that fails, and exactly once when a
 * {@code FAILED} adjustment is re-approved. The fact's {@code unitCost} is the one the costing engine
 * stamped on the posted row, and the availability snapshot facts are queued alongside.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("inventory.adjustment.posted producer (#2190)")
class InventoryAdjustedFactProducerTest {

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

    private static final String AUDITOR = "auditor-2190";

    @Autowired
    private CycleCountAdjustmentService adjustmentService;

    @Autowired
    private CycleCountService cycleCountService;

    @Autowired
    private StockMovementService stockMovementService;

    @MockitoSpyBean
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private InventoryLedgerEntryRepository ledgerRepository;

    @Autowired
    private CycleCountAdjustmentRepository adjustmentRepository;

    @Autowired
    private CycleCountTaskRepository taskRepository;

    @Autowired
    private SkuCostStateRepository costStateRepository;

    @Autowired
    private ApprovalThresholdConfigRepository thresholdConfigRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        TestingAuthenticationToken authentication =
                new TestingAuthenticationToken("manager-2190", "password", "ROLE_MANAGER");
        authentication.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USER_ID, "manager-2190-id",
                GatewaySecurityConstants.DETAIL_USERNAME, "manager-2190",
                GatewaySecurityConstants.DETAIL_LOCATION_SCOPE, LocationScope.unscoped()));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        requireApprovalForAnyVariance();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("approved count gain queues one fact carrying the stamped cost, and the snapshot facts")
    void approvedCountGain_queuesOneFactWithStampedCostAndSnapshots() throws Exception {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();
        receive(sku, location, "10", "4.0000");

        AdjustmentResponse created = createCount(sku, location, "12", "10");
        assertThat(created.getStatus()).isEqualTo(AdjustmentStatus.PENDING_APPROVAL);
        assertThat(factRows(created.getAdjustmentId())).isEmpty();

        adjustmentService.approveAdjustment(
                created.getAdjustmentId(), ApproveAdjustmentRequest.builder().build(), null);

        InventoryLedgerEntry posted =
                ledgerRepository.findByAdjustmentId(created.getAdjustmentId()).orElseThrow();
        List<JsonNode> facts = factPayloads(created.getAdjustmentId());
        assertThat(facts).hasSize(1);
        JsonNode fact = facts.getFirst();
        assertThat(fact.get("adjustmentKind").asString()).isEqualTo(InventoryAdjustedV1.KIND_CYCLE_COUNT);
        assertThat(fact.get("ledgerEventType").asString()).isEqualTo("COUNT_VARIANCE_IN");
        assertThat(fact.get("ledgerEntryId").asString())
                .isEqualTo(posted.getLedgerEntryId().toString());
        assertThat(fact.get("sku").asString()).isEqualTo(sku);
        assertThat(fact.get("locationId").asString()).isEqualTo(location.toString());
        assertThat(fact.get("quantityDelta").decimalValue()).isEqualByComparingTo("2");
        assertThat(fact.get("unitCost").decimalValue()).isEqualByComparingTo(posted.getUnitCost());
        assertThat(fact.get("unitCost").decimalValue()).isEqualByComparingTo("4.0000");
        assertThat(fact.get("costSource").asString()).isEqualTo("AVERAGE");

        List<OutboxEvent> rows = outboxEventRepository.findAll();
        assertThat(rows)
                .anyMatch(row -> row.getPayload().contains(InventoryAvailabilityUpdatedV1.EVENT_TYPE)
                        && row.getPayload().contains(sku));
        assertThat(rows)
                .anyMatch(row -> row.getPayload().contains(StorageLocationOnHandUpdatedV1.EVENT_TYPE)
                        && row.getPayload().contains(location.toString()));
    }

    @Test
    @DisplayName("a count gain values at the current average and leaves it unchanged, even with a stale snapshot")
    void countGain_leavesRunningAverageUnchanged() throws Exception {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();
        receive(sku, location, "10", "4.0000");
        // Snapshot taken at avg 4; a later receipt moves the average to 6 before approval.
        AdjustmentResponse created = createCount(sku, location, "12", "10");
        receive(sku, location, "10", "8.0000");
        assertThat(avgCost(sku)).isEqualByComparingTo("6");

        adjustmentService.approveAdjustment(
                created.getAdjustmentId(), ApproveAdjustmentRequest.builder().build(), null);

        // Before #2190 the gain posted at the stale snapshot (4) as a receipt, re-blending the
        // average down; now it enters at the current average and the average is untouched.
        assertThat(avgCost(sku)).isEqualByComparingTo("6");
        InventoryLedgerEntry posted =
                ledgerRepository.findByAdjustmentId(created.getAdjustmentId()).orElseThrow();
        assertThat(posted.getUnitCost()).isEqualByComparingTo("6");
        assertThat(factPayloads(created.getAdjustmentId())
                        .getFirst()
                        .get("unitCost")
                        .decimalValue())
                .isEqualByComparingTo("6");
    }

    @Test
    @DisplayName("a rejected adjustment queues no fact")
    void rejected_queuesNoFact() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();
        receive(sku, location, "10", "4.0000");
        AdjustmentResponse created = createCount(sku, location, "8", "10");

        adjustmentService.rejectAdjustment(
                created.getAdjustmentId(),
                RejectAdjustmentRequest.builder()
                        .rejectorUserId("manager-2190")
                        .rejectionReason("recount")
                        .build());

        assertThat(factRows(created.getAdjustmentId())).isEmpty();
    }

    @Test
    @DisplayName("a zero recomputed variance posts nothing and queues no fact")
    void zeroRecomputedVariance_queuesNoFact() {
        String sku = uniqueSku();
        // Non-UUID bin: global on-hand math, no ledger location (as CycleCountConflictDetectionTest).
        receive(sku, null, "10", "4.0000");
        CycleCountTask task = taskRepository.save(CycleCountTask.builder()
                .binLocation("BIN-2190-ZERO")
                .itemSku(sku)
                .itemDescription("#2190 zero variance")
                .expectedQuantity(new BigDecimal("10"))
                .auditorId(AUDITOR)
                .status(TaskStatus.ASSIGNED)
                .countEntriesCount(0)
                .build());
        tick();
        ledgerPostingService.post(InventoryLedgerEntry.builder()
                .stockItemId(sku)
                .eventType(InventoryLedgerEventType.GOODS_ISSUE)
                .changeInQuantity(new BigDecimal("-3"))
                .quantityAfter(new BigDecimal("7"))
                .transactionUserId("seed")
                .build());
        CountResponse count = cycleCountService.submitCount(SubmitCountRequest.builder()
                .taskId(task.getTaskId())
                .auditorId(AUDITOR)
                .actualQuantity(new BigDecimal("7"))
                .build());
        assertThat(count.getTaskStatus()).isEqualTo(TaskStatus.CONFLICT);
        AdjustmentResponse created = adjustmentService.createAdjustment(CreateAdjustmentRequest.builder()
                .stockItemId(sku)
                .taskId(task.getTaskId())
                .reasonCode("CYCLE_COUNT_VARIANCE")
                .countedQuantity(new BigDecimal("7"))
                .quantityOnHandBefore(new BigDecimal("10"))
                .createdByUserId(AUDITOR)
                .build());

        AdjustmentResponse approved = adjustmentService.approveAdjustment(
                created.getAdjustmentId(), ApproveAdjustmentRequest.builder().build(), null);

        assertThat(approved.getQuantityChange()).isEqualByComparingTo("0");
        assertThat(ledgerRepository.findByAdjustmentId(created.getAdjustmentId()))
                .isEmpty();
        assertThat(factRows(created.getAdjustmentId())).isEmpty();
    }

    @Test
    @DisplayName("a failed posting queues no fact; re-approving the FAILED adjustment posts once and emits once")
    void failedPosting_queuesNoFact_thenRetryEmitsOnce() throws Exception {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();
        receive(sku, location, "10", "4.0000");
        AdjustmentResponse created = createCount(sku, location, "8", "10");

        doThrow(new IllegalStateException("summary row lock timed out"))
                .when(ledgerPostingService)
                .post(argThat((InventoryLedgerEntry entry) -> entry != null
                        && sku.equals(entry.getStockItemId())
                        && entry.getEventType() == InventoryLedgerEventType.COUNT_VARIANCE_OUT));

        assertThatThrownBy(() -> adjustmentService.approveAdjustment(
                        created.getAdjustmentId(),
                        ApproveAdjustmentRequest.builder().build(),
                        null))
                .isInstanceOf(AdjustmentLedgerPostingException.class);
        assertThat(adjustmentRepository
                        .findById(created.getAdjustmentId())
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(AdjustmentStatus.FAILED);
        assertThat(ledgerRepository.findByAdjustmentId(created.getAdjustmentId()))
                .isEmpty();
        assertThat(factRows(created.getAdjustmentId())).isEmpty();

        org.mockito.Mockito.reset(ledgerPostingService);

        AdjustmentResponse retried = adjustmentService.approveAdjustment(
                created.getAdjustmentId(), ApproveAdjustmentRequest.builder().build(), null);

        assertThat(retried.getStatus()).isEqualTo(AdjustmentStatus.POSTED);
        List<JsonNode> facts = factPayloads(created.getAdjustmentId());
        assertThat(facts).hasSize(1);
        assertThat(facts.getFirst().get("quantityDelta").decimalValue()).isEqualByComparingTo("-2");
        assertThat(facts.getFirst().get("ledgerEntryId").asString())
                .isEqualTo(retried.getLedgerEntryId().toString());
    }

    @Test
    @DisplayName("an approved manual adjustment request queues one MANUAL_ADJUSTMENT fact and the snapshots")
    void approvedManualAdjustment_queuesOneFact() throws Exception {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();
        receive(sku, location, "10", "5.0000");
        AdjustmentRequestResponse created = stockMovementService.createAdjustmentRequest(
                CreateAdjustmentRequestDto.builder()
                        .productSku(sku)
                        .locationId(location)
                        .quantity(new BigDecimal("-4"))
                        .reasonCode("DAMAGE")
                        .unitOfMeasure("EACH")
                        .build(),
                "requester-2190");
        UUID requestId = created.getAdjustmentRequestId();
        assertThat(factRows(requestId)).isEmpty();

        stockMovementService.approveAdjustmentRequest(requestId, "manager-2190");

        InventoryLedgerEntry posted =
                ledgerRepository.findByAdjustmentId(requestId).orElseThrow();
        List<JsonNode> facts = factPayloads(requestId);
        assertThat(facts).hasSize(1);
        JsonNode fact = facts.getFirst();
        assertThat(fact.get("adjustmentKind").asString()).isEqualTo(InventoryAdjustedV1.KIND_MANUAL_ADJUSTMENT);
        assertThat(fact.get("ledgerEventType").asString()).isEqualTo("ADJUSTMENT_OUT");
        assertThat(fact.get("ledgerEntryId").asString())
                .isEqualTo(posted.getLedgerEntryId().toString());
        assertThat(fact.get("quantityDelta").decimalValue()).isEqualByComparingTo("-4");
        assertThat(fact.get("unitCost").decimalValue()).isEqualByComparingTo(posted.getUnitCost());
        assertThat(fact.get("costSource").asString()).isEqualTo("AVERAGE");
        assertThat(fact.get("reasonCode").asString()).isEqualTo("DAMAGE");

        assertThat(outboxEventRepository.findAll())
                .anyMatch(row -> row.getPayload().contains(InventoryAvailabilityUpdatedV1.EVENT_TYPE)
                        && row.getPayload().contains(sku));
        assertThat(outboxEventRepository.findAll())
                .anyMatch(row -> row.getPayload().contains(StorageLocationOnHandUpdatedV1.EVENT_TYPE)
                        && row.getPayload().contains(location.toString()));
    }

    @Test
    @DisplayName("an uncosted SKU emits the fact with a null unitCost and costSource NONE")
    void uncostedSku_emitsNullCostAndSourceNone() throws Exception {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();
        AdjustmentRequestResponse created = stockMovementService.createAdjustmentRequest(
                CreateAdjustmentRequestDto.builder()
                        .productSku(sku)
                        .locationId(location)
                        .quantity(new BigDecimal("3"))
                        .reasonCode("FOUND")
                        .unitOfMeasure("EACH")
                        .build(),
                "requester-2190");

        stockMovementService.approveAdjustmentRequest(created.getAdjustmentRequestId(), "manager-2190");

        List<JsonNode> facts = factPayloads(created.getAdjustmentRequestId());
        assertThat(facts).hasSize(1);
        assertThat(facts.getFirst().get("unitCost").isNull()).isTrue();
        assertThat(facts.getFirst().get("costSource").asString()).isEqualTo("NONE");
        assertThat(facts.getFirst().get("quantityDelta").decimalValue()).isEqualByComparingTo("3");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void receive(String sku, UUID location, String quantity, String unitCost) {
        ledgerPostingService.post(InventoryLedgerEntry.builder()
                .stockItemId(sku)
                .locationId(location)
                .eventType(InventoryLedgerEventType.GOODS_RECEIPT)
                .changeInQuantity(new BigDecimal(quantity))
                .quantityAfter(new BigDecimal(quantity))
                .unitCost(new BigDecimal(unitCost))
                .transactionUserId("seed")
                .build());
    }

    private AdjustmentResponse createCount(String sku, UUID location, String counted, String onHandBefore) {
        return adjustmentService.createAdjustment(CreateAdjustmentRequest.builder()
                .stockItemId(sku)
                .locationId(location)
                .reasonCode("CYCLE_COUNT_VARIANCE")
                .countedQuantity(new BigDecimal(counted))
                .quantityOnHandBefore(new BigDecimal(onHandBefore))
                .createdByUserId(AUDITOR)
                .build());
    }

    /**
     * Forces every variance into PENDING_APPROVAL. {@code approval_tier} is unique and the H2
     * database is shared across test classes, so the TIER_1 row is created or tightened in place.
     */
    private void requireApprovalForAnyVariance() {
        ApprovalThresholdConfig tier1 = thresholdConfigRepository.findAll().stream()
                .filter(config -> config.getApprovalTier() == ApprovalTier.TIER_1_MANAGER
                        && config.getFlowType() == ApprovalFlowType.CYCLE_COUNT)
                .findFirst()
                .orElseGet(() -> ApprovalThresholdConfig.builder()
                        .flowType(ApprovalFlowType.CYCLE_COUNT)
                        .approvalTier(ApprovalTier.TIER_1_MANAGER)
                        .build());
        tier1.setUnitVarianceThreshold(1);
        tier1.setValueVarianceThreshold(new BigDecimal("0.01"));
        tier1.setPercentageVarianceThreshold(new BigDecimal("0.01"));
        tier1.setActive(true);
        thresholdConfigRepository.save(tier1);
    }

    private BigDecimal avgCost(String sku) {
        return costStateRepository.findByStockItemId(sku).orElseThrow().getAvgCost();
    }

    private List<OutboxEvent> factRows(UUID adjustmentId) {
        return outboxEventRepository.findAll().stream()
                .filter(row -> row.getPayload().contains(InventoryAdjustedV1.EVENT_TYPE)
                        && row.getPayload().contains(adjustmentId.toString()))
                .toList();
    }

    private List<JsonNode> factPayloads(UUID adjustmentId) throws Exception {
        List<JsonNode> payloads = new java.util.ArrayList<>();
        for (OutboxEvent row : factRows(adjustmentId)) {
            JsonNode envelope = objectMapper.readTree(row.getPayload());
            assertThat(envelope.get("eventType").asString()).isEqualTo(InventoryAdjustedV1.EVENT_TYPE);
            assertThat(envelope.get("aggregateId").asString()).isEqualTo(adjustmentId.toString());
            payloads.add(envelope.get("payload"));
        }
        return payloads;
    }

    private static String uniqueSku() {
        return "SKU-2190-" + UUID.randomUUID();
    }

    /** Movement timestamps must be strictly after the task snapshot. */
    private static void tick() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}

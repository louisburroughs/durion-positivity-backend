package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.inventory.internal.dto.cyclecount.AdjustmentResponse;
import com.positivity.inventory.internal.dto.cyclecount.ApproveAdjustmentRequest;
import com.positivity.inventory.internal.dto.cyclecount.CountResponse;
import com.positivity.inventory.internal.dto.cyclecount.CreateAdjustmentRequest;
import com.positivity.inventory.internal.dto.cyclecount.SubmitCountRequest;
import com.positivity.inventory.internal.entity.ApprovalThresholdConfig;
import com.positivity.inventory.internal.entity.CycleCountTask;
import com.positivity.inventory.internal.entity.CycleCountTolerance;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.AdjustmentStatus;
import com.positivity.inventory.internal.enums.ApprovalTier;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.TaskStatus;
import com.positivity.inventory.internal.repository.ApprovalThresholdConfigRepository;
import com.positivity.inventory.internal.repository.CycleCountAdjustmentRepository;
import com.positivity.inventory.internal.repository.CycleCountTaskRepository;
import com.positivity.inventory.internal.repository.CycleCountToleranceRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.service.CycleCountAdjustmentService;
import com.positivity.inventory.service.CycleCountService;
import com.positivity.security.common.GatewaySecurityConstants;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

/**
 * End-to-end tolerance-based cycle-count reconciliation (ADR-0055 stage 4, #1416), against the
 * owner's worked examples from issue #1414's comment:
 *
 * <pre>
 * | Book     | Measured | Tolerance | Result                              |
 * |---------:|---------:|----------:|-------------------------------------|
 * | 8,000 gal| 7,970 gal| ±50 gal   | Accept count; no adjustment         |
 * | 8,000 gal| 7,890 gal| ±50 gal   | Investigate and approve adjustment  |
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Tolerance-based cycle-count reconciliation (#1416)")
class CycleCountToleranceReconciliationTest {

    private static final String AUDITOR = "auditor-tol";
    private static final String LOCATION = "TANK-04";

    @Autowired
    private CycleCountService cycleCountService;

    @Autowired
    private CycleCountAdjustmentService adjustmentService;

    @Autowired
    private CycleCountTaskRepository taskRepository;

    @Autowired
    private CycleCountToleranceRepository toleranceRepository;

    @Autowired
    private CycleCountAdjustmentRepository adjustmentRepository;

    @Autowired
    private InventoryLedgerEntryRepository ledgerRepository;

    @Autowired
    private ApprovalThresholdConfigRepository thresholdConfigRepository;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    /** Establishes real on-hand via the ledger, exactly as the book quantity assumes. */
    private void seedOnHand(UUID productId, BigDecimal quantity) {
        ledgerPostingService.post(InventoryLedgerEntry.builder()
                .stockItemId(productId.toString())
                .eventType(InventoryLedgerEventType.GOODS_RECEIPT)
                .changeInQuantity(quantity)
                .quantityAfter(quantity)
                .transactionUserId("tolerance-test-seed")
                .build());
    }

    @BeforeEach
    void authenticate() {
        TestingAuthenticationToken authentication =
                new TestingAuthenticationToken("manager-tol", "password", "ROLE_MANAGER");
        authentication.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USER_ID, "manager-tol-id",
                GatewaySecurityConstants.DETAIL_USERNAME, "manager-tol"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Forces adjustments into PENDING_APPROVAL (approval_tier is unique — seed once). */
    private void ensureTier1ApprovalThreshold() {
        boolean present = thresholdConfigRepository.findAll().stream()
                .anyMatch(config -> config.getApprovalTier() == ApprovalTier.TIER_1_MANAGER);
        if (!present) {
            thresholdConfigRepository.save(ApprovalThresholdConfig.builder()
                    .approvalTier(ApprovalTier.TIER_1_MANAGER)
                    .unitVarianceThreshold(1)
                    .valueVarianceThreshold(new BigDecimal("0.01"))
                    .percentageVarianceThreshold(new BigDecimal("0.01"))
                    .active(true)
                    .build());
        }
    }

    private UUID seedTolerance(UUID productId, String location, String absolute) {
        return toleranceRepository
                .save(CycleCountTolerance.builder()
                        .productId(productId)
                        .storageLocation(location)
                        .absoluteTolerance(new BigDecimal(absolute))
                        .active(true)
                        .createdBy("tester")
                        .build())
                .getToleranceId();
    }

    private CycleCountTask seedTask(UUID productId, String location, String book) {
        return taskRepository.save(CycleCountTask.builder()
                .binLocation(location)
                .itemSku(productId.toString())
                .itemDescription("Bulk tank")
                .expectedQuantity(new BigDecimal(book))
                .auditorId(AUDITOR)
                .status(TaskStatus.ASSIGNED)
                .countEntriesCount(0)
                .build());
    }

    @Test
    @DisplayName("book 8000, measured 7970, tolerance ±50: accepted, no adjustment, ledger untouched")
    void withinTolerance_acceptsWithNoAdjustmentAndNoLedgerEntry() {
        UUID productId = UUID.randomUUID();
        seedTolerance(productId, LOCATION, "50");
        seedOnHand(productId, new BigDecimal("8000"));
        CycleCountTask task = seedTask(productId, LOCATION, "8000");
        int ledgerEntriesBeforeCount = ledgerRepository
                .findByStockItemIdOrderByTimestampAsc(productId.toString())
                .size();

        CountResponse response = cycleCountService.submitCount(SubmitCountRequest.builder()
                .taskId(task.getTaskId())
                .auditorId(AUDITOR)
                .actualQuantity(new BigDecimal("7970"))
                .build());

        assertThat(response.getVariance()).isEqualByComparingTo("-30");
        assertThat(response.isWithinTolerance()).isTrue();
        assertThat(response.getTaskStatus()).isEqualTo(TaskStatus.ACCEPTED_WITHIN_TOLERANCE);
        assertThat(taskRepository.findById(task.getTaskId()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.ACCEPTED_WITHIN_TOLERANCE);

        // Count and adjustment are separate transactions: within tolerance, no adjustment is
        // ever created and the ledger gains no new entry from the count itself.
        assertThat(adjustmentRepository.findByStockItemId(productId)).isEmpty();
        assertThat(ledgerRepository.findByStockItemIdOrderByTimestampAsc(productId.toString()))
                .hasSize(ledgerEntriesBeforeCount);
    }

    @Test
    @DisplayName("book 8000, measured 7890, tolerance ±50: flagged, requires approval, posts -110 on approval")
    void exceedingTolerance_flagsForReviewThenApprovedAdjustmentPostsSeparately() {
        ensureTier1ApprovalThreshold();
        UUID productId = UUID.randomUUID();
        seedTolerance(productId, LOCATION, "50");
        seedOnHand(productId, new BigDecimal("8000"));
        CycleCountTask task = seedTask(productId, LOCATION, "8000");
        int ledgerEntriesBeforeCount = ledgerRepository
                .findByStockItemIdOrderByTimestampAsc(productId.toString())
                .size();

        CountResponse count = cycleCountService.submitCount(SubmitCountRequest.builder()
                .taskId(task.getTaskId())
                .auditorId(AUDITOR)
                .actualQuantity(new BigDecimal("7890"))
                .varianceReason("Suspected meter drift")
                .build());

        assertThat(count.getVariance()).isEqualByComparingTo("-110");
        assertThat(count.isWithinTolerance()).isFalse();
        // Unchanged pre-#1416 path: exceeding tolerance behaves exactly as an unconfigured
        // (zero-tolerance) variance always has — flagged for review, no adjustment yet.
        assertThat(count.getTaskStatus()).isEqualTo(TaskStatus.COUNTED_PENDING_REVIEW);

        // The count alone never touches the ledger or creates an adjustment — separate
        // transactions, exactly as the owner's spec requires.
        assertThat(adjustmentRepository.findByStockItemId(productId)).isEmpty();
        assertThat(ledgerRepository.findByStockItemIdOrderByTimestampAsc(productId.toString()))
                .hasSize(ledgerEntriesBeforeCount);

        // A reviewer investigates and explicitly creates the adjustment — a second, separate
        // transaction.
        AdjustmentResponse created = adjustmentService.createAdjustment(CreateAdjustmentRequest.builder()
                .stockItemId(productId)
                .taskId(task.getTaskId())
                .reasonCode("Suspected meter drift")
                .countedQuantity(new BigDecimal("7890"))
                .quantityOnHandBefore(new BigDecimal("8000"))
                .costAtTimeOfAdjustment(new BigDecimal("2.00"))
                .createdByUserId(AUDITOR)
                .build());

        assertThat(created.getStatus()).isEqualTo(AdjustmentStatus.PENDING_APPROVAL);
        assertThat(created.getQuantityChange()).isEqualByComparingTo("-110");
        // Nothing posted yet: approval is a third, still-separate step.
        assertThat(ledgerRepository.findByStockItemIdOrderByTimestampAsc(productId.toString()))
                .hasSize(ledgerEntriesBeforeCount);

        AdjustmentResponse approved = adjustmentService.approveAdjustment(
                created.getAdjustmentId(), ApproveAdjustmentRequest.builder().build(), null);

        assertThat(approved.getStatus()).isEqualTo(AdjustmentStatus.POSTED);
        assertThat(approved.getQuantityChange()).isEqualByComparingTo("-110");

        List<InventoryLedgerEntry> posted = ledgerRepository.findByStockItemIdOrderByTimestampAsc(productId.toString());
        assertThat(posted).hasSize(ledgerEntriesBeforeCount + 1);
        InventoryLedgerEntry countVarianceEntry = posted.getLast();
        assertThat(countVarianceEntry.getEventType()).isEqualTo(InventoryLedgerEventType.COUNT_VARIANCE_OUT);
        assertThat(countVarianceEntry.getChangeInQuantity()).isEqualByComparingTo("-110");

        assertThat(taskRepository.findById(task.getTaskId()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.APPROVED);
    }

    @Test
    @DisplayName("tolerance scope precedence: product+location beats a broader product-only tolerance")
    void tolerancePrecedence_productAndLocationBeatsProductOnly() {
        UUID productId = UUID.randomUUID();
        seedTolerance(productId, null, "10"); // broad, product-only
        seedTolerance(productId, LOCATION, "50"); // most specific
        CycleCountTask task = seedTask(productId, LOCATION, "8000");

        // Variance of 30 exceeds the product-only bound (10) but fits the product+location
        // bound (50) — proves the more specific row is the one actually applied.
        CountResponse response = cycleCountService.submitCount(SubmitCountRequest.builder()
                .taskId(task.getTaskId())
                .auditorId(AUDITOR)
                .actualQuantity(new BigDecimal("7970"))
                .build());

        assertThat(response.isWithinTolerance()).isTrue();
        assertThat(response.getTaskStatus()).isEqualTo(TaskStatus.ACCEPTED_WITHIN_TOLERANCE);
    }

    @Test
    @DisplayName("no tolerance configured: any nonzero variance is flagged (today's behavior, preserved)")
    void noToleranceConfigured_anyNonzeroVarianceExceeds() {
        UUID productId = UUID.randomUUID();
        CycleCountTask task = seedTask(productId, LOCATION, "100");

        CountResponse response = cycleCountService.submitCount(SubmitCountRequest.builder()
                .taskId(task.getTaskId())
                .auditorId(AUDITOR)
                .actualQuantity(new BigDecimal("99"))
                .build());

        assertThat(response.isWithinTolerance()).isFalse();
        assertThat(response.getTaskStatus()).isEqualTo(TaskStatus.COUNTED_PENDING_REVIEW);
    }

    @Test
    @DisplayName("no tolerance configured: an exact match is still accepted with no adjustment")
    void noToleranceConfigured_exactMatchIsAccepted() {
        UUID productId = UUID.randomUUID();
        CycleCountTask task = seedTask(productId, LOCATION, "100");

        CountResponse response = cycleCountService.submitCount(SubmitCountRequest.builder()
                .taskId(task.getTaskId())
                .auditorId(AUDITOR)
                .actualQuantity(new BigDecimal("100"))
                .build());

        assertThat(response.isWithinTolerance()).isTrue();
        assertThat(response.getTaskStatus()).isEqualTo(TaskStatus.ACCEPTED_WITHIN_TOLERANCE);
        assertThat(adjustmentRepository.findByStockItemId(productId)).isEmpty();
    }
}

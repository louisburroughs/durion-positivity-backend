package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.positivity.inventory.internal.dto.cyclecount.AdjustmentResponse;
import com.positivity.inventory.internal.dto.cyclecount.ApproveAdjustmentRequest;
import com.positivity.inventory.internal.dto.cyclecount.CountResponse;
import com.positivity.inventory.internal.dto.cyclecount.CreateAdjustmentRequest;
import com.positivity.inventory.internal.dto.cyclecount.InterferingMovementResponse;
import com.positivity.inventory.internal.dto.cyclecount.SubmitCountRequest;
import com.positivity.inventory.internal.dto.cyclecount.SubmitRecountRequest;
import com.positivity.inventory.internal.entity.ApprovalThresholdConfig;
import com.positivity.inventory.internal.entity.CycleCountTask;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.AdjustmentStatus;
import com.positivity.inventory.internal.enums.ApprovalTier;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.TaskStatus;
import com.positivity.inventory.internal.exception.CycleCountConflictException;
import com.positivity.inventory.internal.repository.ApprovalThresholdConfigRepository;
import com.positivity.inventory.internal.repository.CycleCountTaskRepository;
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
 * End-to-end tests for cycle-count conflict detection (odoo-parity I2, issue
 * #1026): movements between task creation and count submission / adjustment
 * approval flag the task CONFLICT, approval on a CONFLICT task recomputes the
 * variance against CURRENT on-hand, and the interfering movements are listable.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Cycle count conflict detection (I2)")
class CycleCountConflictDetectionTest {

    private static final String AUDITOR = "auditor-i2";

    @Autowired
    private CycleCountService cycleCountService;

    @Autowired
    private CycleCountAdjustmentService adjustmentService;

    @Autowired
    private CycleCountTaskRepository taskRepository;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private InventoryLedgerEntryRepository ledgerRepository;

    @Autowired
    private ApprovalThresholdConfigRepository thresholdConfigRepository;

    @BeforeEach
    void authenticateApprover() {
        TestingAuthenticationToken authentication =
                new TestingAuthenticationToken("manager-i2", "password", "ROLE_MANAGER");
        authentication.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USER_ID, "manager-i2-id",
                GatewaySecurityConstants.DETAIL_USERNAME, "manager-i2"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void post(String sku, UUID locationId, InventoryLedgerEventType type, BigDecimal change, int after) {
        ledgerPostingService.post(InventoryLedgerEntry.builder()
                .stockItemId(sku)
                .locationId(locationId)
                .eventType(type)
                .changeInQuantity(change)
                .quantityAfter(BigDecimal.valueOf(after))
                .transactionUserId("i2-test")
                .build());
    }

    private CycleCountTask task(String binLocation, String sku, int expectedQuantity) {
        return taskRepository.save(CycleCountTask.builder()
                .binLocation(binLocation)
                .itemSku(sku)
                .itemDescription("I2 test item")
                .expectedQuantity(BigDecimal.valueOf(expectedQuantity))
                .auditorId(AUDITOR)
                .status(TaskStatus.ASSIGNED)
                .countEntriesCount(0)
                .build());
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

    /** Movement timestamps must be strictly after the task snapshot. */
    private static void tick() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("movement posted mid-count flags the task CONFLICT at submission")
    void movementMidCount_flagsConflictAtSubmission() {
        String sku = "SKU-I2-" + UUID.randomUUID();
        UUID location = UUID.randomUUID();
        post(sku, location, InventoryLedgerEventType.GOODS_RECEIPT, new BigDecimal("10"), 10);
        CycleCountTask task = task(location.toString(), sku, 10);
        tick();
        post(sku, location, InventoryLedgerEventType.GOODS_ISSUE, new BigDecimal("-3"), 7);

        CountResponse response = cycleCountService.submitCount(SubmitCountRequest.builder()
                .taskId(task.getTaskId())
                .auditorId(AUDITOR)
                .actualQuantity(BigDecimal.valueOf(7))
                .build());

        assertThat(response.getTaskStatus()).isEqualTo(TaskStatus.CONFLICT);
        assertThat(taskRepository.findById(task.getTaskId()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.CONFLICT);
    }

    @Test
    @DisplayName("no movement in the window keeps the pre-I2 COUNTED_PENDING_REVIEW flow (regression)")
    void noMovement_staysPendingReview() {
        String sku = "SKU-I2-" + UUID.randomUUID();
        UUID location = UUID.randomUUID();
        post(sku, location, InventoryLedgerEventType.GOODS_RECEIPT, new BigDecimal("10"), 10);
        CycleCountTask task = task(location.toString(), sku, 10);

        CountResponse response = cycleCountService.submitCount(SubmitCountRequest.builder()
                .taskId(task.getTaskId())
                .auditorId(AUDITOR)
                .actualQuantity(BigDecimal.valueOf(8))
                .build());

        assertThat(response.getTaskStatus()).isEqualTo(TaskStatus.COUNTED_PENDING_REVIEW);
        assertThat(response.getVariance()).isEqualByComparingTo("-2");
    }

    @Test
    @DisplayName("recount path from CONFLICT works and stays CONFLICT while movements remain in the window")
    void recountFromConflict_works() {
        String sku = "SKU-I2-" + UUID.randomUUID();
        UUID location = UUID.randomUUID();
        post(sku, location, InventoryLedgerEventType.GOODS_RECEIPT, new BigDecimal("10"), 10);
        CycleCountTask task = task(location.toString(), sku, 10);
        tick();
        post(sku, location, InventoryLedgerEventType.GOODS_ISSUE, new BigDecimal("-3"), 7);

        cycleCountService.submitCount(SubmitCountRequest.builder()
                .taskId(task.getTaskId())
                .auditorId(AUDITOR)
                .actualQuantity(BigDecimal.valueOf(7))
                .build());
        assertThat(taskRepository.findById(task.getTaskId()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.CONFLICT);

        CountResponse recount = cycleCountService.submitRecount(SubmitRecountRequest.builder()
                .taskId(task.getTaskId())
                .auditorId(AUDITOR)
                .actualQuantity(BigDecimal.valueOf(7))
                .permission("TRIGGER_RECOUNT_SELF")
                .build());

        assertThat(recount.getRecountSequenceNumber()).isEqualTo(1);
        // The snapshot is still stale — the movement remains in the window.
        assertThat(recount.getTaskStatus()).isEqualTo(TaskStatus.CONFLICT);
    }

    @Test
    @DisplayName("approval on a CONFLICT task recomputes the variance against current on-hand before posting")
    void approvalOnConflict_recomputesVarianceAgainstCurrentOnHand() {
        ensureTier1ApprovalThreshold();

        UUID stockItemId = UUID.randomUUID();
        String sku = stockItemId.toString();
        // Non-UUID bin ⇒ detection and recomputation both use the global
        // (SKU-wide) on-hand math, like the adjustment posting path itself.
        post(sku, null, InventoryLedgerEventType.GOODS_RECEIPT, new BigDecimal("10"), 10);
        CycleCountTask task = task("BIN-I2-APPROVE", sku, 10);
        tick();
        post(sku, null, InventoryLedgerEventType.GOODS_ISSUE, new BigDecimal("-3"), 7);

        CountResponse count = cycleCountService.submitCount(SubmitCountRequest.builder()
                .taskId(task.getTaskId())
                .auditorId(AUDITOR)
                .actualQuantity(BigDecimal.valueOf(5))
                .build());
        assertThat(count.getTaskStatus()).isEqualTo(TaskStatus.CONFLICT);

        AdjustmentResponse created = adjustmentService.createAdjustment(CreateAdjustmentRequest.builder()
                .stockItemId(stockItemId)
                .taskId(task.getTaskId())
                .reasonCode("CYCLE_COUNT_VARIANCE")
                .countedQuantity(new BigDecimal("5"))
                .quantityOnHandBefore(new BigDecimal("10")) // stale snapshot: would imply -5
                .costAtTimeOfAdjustment(new BigDecimal("10.00"))
                .createdByUserId(AUDITOR)
                .build());
        assertThat(created.getStatus()).isEqualTo(AdjustmentStatus.PENDING_APPROVAL);

        AdjustmentResponse approved = adjustmentService.approveAdjustment(
                created.getAdjustmentId(), ApproveAdjustmentRequest.builder().build(), null);

        // Recomputed against CURRENT on-hand (7), never the stale snapshot (10):
        // counted 5 - current 7 = -2, not -5.
        assertThat(approved.getStatus()).isEqualTo(AdjustmentStatus.POSTED);
        assertThat(approved.getQuantityChange()).isEqualByComparingTo("-2");
        assertThat(approved.getQuantityOnHandBefore()).isEqualByComparingTo("7");

        InventoryLedgerEntry posted =
                ledgerRepository.findByAdjustmentId(created.getAdjustmentId()).orElseThrow();
        assertThat(posted.getEventType()).isEqualTo(InventoryLedgerEventType.COUNT_VARIANCE_OUT);
        assertThat(posted.getChangeInQuantity()).isEqualByComparingTo("-2");

        assertThat(taskRepository.findById(task.getTaskId()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.APPROVED);
    }

    @Test
    @DisplayName("movements after a clean count are detected at approval: task flagged CONFLICT, approval rejected")
    void approvalDetectsLateConflict_flagsTaskAndRejects() {
        ensureTier1ApprovalThreshold();

        UUID stockItemId = UUID.randomUUID();
        String sku = stockItemId.toString();
        post(sku, null, InventoryLedgerEventType.GOODS_RECEIPT, new BigDecimal("10"), 10);
        CycleCountTask task = task("BIN-I2-LATE", sku, 10);

        CountResponse count = cycleCountService.submitCount(SubmitCountRequest.builder()
                .taskId(task.getTaskId())
                .auditorId(AUDITOR)
                .actualQuantity(BigDecimal.valueOf(8))
                .build());
        assertThat(count.getTaskStatus()).isEqualTo(TaskStatus.COUNTED_PENDING_REVIEW);

        AdjustmentResponse created = adjustmentService.createAdjustment(CreateAdjustmentRequest.builder()
                .stockItemId(stockItemId)
                .taskId(task.getTaskId())
                .reasonCode("CYCLE_COUNT_VARIANCE")
                .countedQuantity(new BigDecimal("8"))
                .quantityOnHandBefore(new BigDecimal("10"))
                .costAtTimeOfAdjustment(new BigDecimal("10.00"))
                .createdByUserId(AUDITOR)
                .build());
        assertThat(created.getStatus()).isEqualTo(AdjustmentStatus.PENDING_APPROVAL);

        // Movement lands between count and approval.
        tick();
        post(sku, null, InventoryLedgerEventType.GOODS_ISSUE, new BigDecimal("-4"), 6);

        assertThatExceptionOfType(CycleCountConflictException.class)
                .isThrownBy(() -> adjustmentService.approveAdjustment(
                        created.getAdjustmentId(),
                        ApproveAdjustmentRequest.builder().build(),
                        null));

        // Task flagged in its own transaction; adjustment untouched.
        assertThat(taskRepository.findById(task.getTaskId()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.CONFLICT);
        assertThat(adjustmentService.getAdjustment(created.getAdjustmentId()).getStatus())
                .isEqualTo(AdjustmentStatus.PENDING_APPROVAL);

        // Second approval is the explicit reviewer choice: recompute and post.
        AdjustmentResponse approved = adjustmentService.approveAdjustment(
                created.getAdjustmentId(), ApproveAdjustmentRequest.builder().build(), null);

        // counted 8 - current 6 = +2 → COUNT_VARIANCE_IN.
        assertThat(approved.getQuantityChange()).isEqualByComparingTo("2");
        InventoryLedgerEntry posted =
                ledgerRepository.findByAdjustmentId(created.getAdjustmentId()).orElseThrow();
        assertThat(posted.getEventType()).isEqualTo(InventoryLedgerEventType.COUNT_VARIANCE_IN);
        assertThat(posted.getChangeInQuantity()).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("interfering-movements listing contains exactly the in-window entries for the task's SKU/location")
    void interferingMovements_listsExactlyInWindowEntries() {
        String sku = "SKU-I2-" + UUID.randomUUID();
        UUID location = UUID.randomUUID();
        UUID otherLocation = UUID.randomUUID();
        // Before the task snapshot — must NOT be listed.
        post(sku, location, InventoryLedgerEventType.GOODS_RECEIPT, new BigDecimal("5"), 5);
        CycleCountTask task = task(location.toString(), sku, 5);
        tick();
        // In-window entries at the task's location — must be listed, in order.
        post(sku, location, InventoryLedgerEventType.GOODS_ISSUE, new BigDecimal("-2"), 3);
        post(sku, location, InventoryLedgerEventType.GOODS_RECEIPT, new BigDecimal("3"), 6);
        // In-window but other location — must NOT be listed.
        post(sku, otherLocation, InventoryLedgerEventType.GOODS_RECEIPT, new BigDecimal("7"), 7);

        List<InterferingMovementResponse> movements = cycleCountService.getInterferingMovements(task.getTaskId());

        assertThat(movements).hasSize(2);
        assertThat(movements.get(0).getEventType()).isEqualTo(InventoryLedgerEventType.GOODS_ISSUE);
        assertThat(movements.get(0).getChangeInQuantity()).isEqualByComparingTo("-2");
        assertThat(movements.get(1).getEventType()).isEqualTo(InventoryLedgerEventType.GOODS_RECEIPT);
        assertThat(movements.get(1).getChangeInQuantity()).isEqualByComparingTo("3");
        assertThat(movements)
                .allSatisfy(movement -> assertThat(movement.getLocationId()).isEqualTo(location));
    }
}

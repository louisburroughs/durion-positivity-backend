package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.inventory.internal.dto.replenishment.ReplenishmentScanResultResponse;
import com.positivity.inventory.internal.entity.ExtStorageLocationReplica;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.PurchaseOrderEntity;
import com.positivity.inventory.internal.entity.PurchaseOrderLineEntity;
import com.positivity.inventory.internal.entity.ReplenishmentPolicy;
import com.positivity.inventory.internal.entity.ReplenishmentTask;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.PurchaseOrderStatus;
import com.positivity.inventory.internal.enums.ReplenishmentStatus;
import com.positivity.inventory.internal.enums.ReplenishmentTriggerType;
import com.positivity.inventory.internal.repository.ExtStorageLocationReplicaRepository;
import com.positivity.inventory.internal.repository.PurchaseOrderRepository;
import com.positivity.inventory.internal.repository.ReplenishmentPolicyRepository;
import com.positivity.inventory.internal.repository.ReplenishmentTaskRepository;
import com.positivity.inventory.service.ReplenishmentService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * End-to-end tests for the batch replenishment scan (CAP-217 / odoo-parity F1,
 * issue #1025; forecast-aware orderpoint math since F2, issue #1040) against the
 * real repositories, the {@code inventory_stock_summary} read model maintained by
 * the ledger posting funnel, and real open-PO forecast supply.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Batch replenishment scan (F1/F2)")
class BatchReplenishmentScanTest {

    private static final AtomicInteger PO_SEQ = new AtomicInteger();

    @Autowired
    private ReplenishmentService replenishmentService;

    @Autowired
    private ReplenishmentPolicyRepository policyRepository;

    @Autowired
    private ReplenishmentTaskRepository taskRepository;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private ExtStorageLocationReplicaRepository storageLocationReplicaRepository;

    private static String uniqueSku() {
        return "SKU-F1-" + UUID.randomUUID();
    }

    private void receive(String sku, UUID locationId, int quantity) {
        ledgerPostingService.post(InventoryLedgerEntry.builder()
                .stockItemId(sku)
                .locationId(locationId)
                .eventType(InventoryLedgerEventType.GOODS_RECEIPT)
                .changeInQuantity(quantity)
                .quantityAfter(quantity)
                .transactionUserId("f1-test")
                .build());
    }

    private ReplenishmentPolicy seedPolicy(String sku, UUID locationId, int min, int max) {
        return policyRepository.save(ReplenishmentPolicy.builder()
                .locationId(locationId)
                .itemSKU(sku)
                .minimumQuantity(min)
                .maximumQuantity(max)
                .build());
    }

    private List<ReplenishmentTask> openTasksFor(String sku) {
        return taskRepository
                .findByStatusIn(List.of(ReplenishmentStatus.PENDING, ReplenishmentStatus.IN_PROGRESS))
                .stream()
                .filter(task -> task.getItemSKU().equals(sku))
                .toList();
    }

    @Test
    @DisplayName("below-minimum policy yields exactly one open task across repeated same-day scans")
    void repeatedScans_sameDay_createExactlyOneOpenTask() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();
        seedPolicy(sku, location, 5, 20);
        receive(sku, location, 3); // on-hand 3 < min 5

        replenishmentService.runBatchReplenishmentScan();
        replenishmentService.runBatchReplenishmentScan();
        replenishmentService.runBatchReplenishmentScan();

        List<ReplenishmentTask> tasks = openTasksFor(sku);
        assertThat(tasks).hasSize(1);
        ReplenishmentTask task = tasks.getFirst();
        assertThat(task.getTriggerType()).isEqualTo(ReplenishmentTriggerType.BATCH);
        assertThat(task.getStatus()).isEqualTo(ReplenishmentStatus.PENDING);
        assertThat(task.getQuantity()).isEqualTo(17); // max 20 - onHand 3
        assertThat(task.getDestinationLocationId()).isEqualTo(location);
    }

    @Test
    @DisplayName("policy at or above minimum triggers nothing")
    void policyAtOrAboveMinimum_createsNoTask() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();
        seedPolicy(sku, location, 5, 20);
        receive(sku, location, 5); // exactly at minimum

        ReplenishmentScanResultResponse result = replenishmentService.runBatchReplenishmentScan();

        assertThat(openTasksFor(sku)).isEmpty();
        assertThat(result.getPoliciesEvaluated()).isPositive();
    }

    @Test
    @DisplayName("open task quantity is refreshed when on-hand drops further")
    void openTask_refreshedWhenNeedGrows() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();
        seedPolicy(sku, location, 5, 20);
        receive(sku, location, 4); // on-hand 4 < min 5 → need 16

        replenishmentService.runBatchReplenishmentScan();
        assertThat(openTasksFor(sku).getFirst().getQuantity()).isEqualTo(16);

        // Stock drops further between scans.
        ledgerPostingService.post(InventoryLedgerEntry.builder()
                .stockItemId(sku)
                .locationId(location)
                .eventType(InventoryLedgerEventType.GOODS_ISSUE)
                .changeInQuantity(-2)
                .quantityAfter(2)
                .transactionUserId("f1-test")
                .build());

        ReplenishmentScanResultResponse second = replenishmentService.runBatchReplenishmentScan();

        List<ReplenishmentTask> tasks = openTasksFor(sku);
        assertThat(tasks).hasSize(1);
        assertThat(tasks.getFirst().getQuantity()).isEqualTo(18); // max 20 - onHand 2
        assertThat(second.getTasksRefreshed()).isPositive();
        assertThat(second.getTasksCreated()).isZero();
    }

    @Test
    @DisplayName("scan summary counts evaluated policies and created tasks")
    void scanSummary_reflectsActivity() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();
        seedPolicy(sku, location, 10, 30);
        // No receipts → summary row absent → on-hand 0 (below min).

        ReplenishmentScanResultResponse result = replenishmentService.runBatchReplenishmentScan();

        assertThat(result.getPoliciesEvaluated()).isPositive();
        assertThat(result.getScanAt()).isNotBlank();
        List<ReplenishmentTask> tasks = openTasksFor(sku);
        assertThat(tasks).hasSize(1);
        assertThat(tasks.getFirst().getQuantity()).isEqualTo(30);
    }

    // ─── F2 (#1040): forecast-aware orderpoint math against real open-PO supply ──────
    // Default lead time is 0 days in the test profile, so leadHorizon == now and the
    // horizon UTC date is TODAY: a PO expected today is inside the horizon (boundary
    // date inclusive), a PO expected tomorrow is outside it.

    @Test
    @DisplayName("F2: below-min on-hand with inbound PO covering the gap within the horizon does not trigger")
    void inboundPoWithinHorizon_coversGap_noTaskCreated() {
        UUID skuId = UUID.randomUUID();
        String sku = skuId.toString();
        UUID location = UUID.randomUUID(); // not in the replica → treated as a site id
        seedPolicy(sku, location, 5, 20);
        receive(sku, location, 3); // on-hand 3 < min 5 — pre-F2 math would trigger
        // Open APPROVED PO for 7 units expected TODAY (== horizon date, boundary inclusive):
        // projected 3 + 7 = 10 >= min 5.
        savePo(PurchaseOrderStatus.APPROVED, location, LocalDate.now(ZoneOffset.UTC), skuId, "7");

        replenishmentService.runBatchReplenishmentScan();

        assertThat(openTasksFor(sku)).isEmpty();
    }

    @Test
    @DisplayName("F2: inbound PO beyond the horizon is excluded — trigger fires and replenishes off the projection")
    void inboundPoBeyondHorizon_triggersOnProjection() {
        UUID skuId = UUID.randomUUID();
        String sku = skuId.toString();
        UUID location = UUID.randomUUID();
        seedPolicy(sku, location, 5, 20);
        receive(sku, location, 3);
        // Same PO but expected TOMORROW — outside the 0-day horizon, excluded from the
        // projection: projected stays 3 < min 5.
        savePo(
                PurchaseOrderStatus.APPROVED,
                location,
                LocalDate.now(ZoneOffset.UTC).plusDays(1),
                skuId,
                "7");

        replenishmentService.runBatchReplenishmentScan();

        List<ReplenishmentTask> tasks = openTasksFor(sku);
        assertThat(tasks).hasSize(1);
        assertThat(tasks.getFirst().getQuantity()).isEqualTo(17); // max 20 - projected 3
    }

    @Test
    @DisplayName("F2: bin-level policy resolves to its parent site for forecast supply")
    void binLevelPolicy_countsSupplyShippedToParentSite() {
        UUID skuId = UUID.randomUUID();
        String sku = skuId.toString();
        UUID bin = UUID.randomUUID();
        UUID site = UUID.randomUUID();
        storageLocationReplicaRepository.save(ExtStorageLocationReplica.builder()
                .storageLocationId(bin)
                .siteId(site)
                .name("F2 pick face")
                .build());
        seedPolicy(sku, bin, 5, 20);
        receive(sku, bin, 3); // on-hand at the BIN the policy governs
        // PO ships to the SITE (POs are never keyed by bin) — bin→site resolution must
        // credit it to the policy's projection: 3 + 7 = 10 >= 5 → no trigger.
        savePo(PurchaseOrderStatus.APPROVED, site, LocalDate.now(ZoneOffset.UTC), skuId, "7");

        replenishmentService.runBatchReplenishmentScan();

        assertThat(openTasksFor(sku)).isEmpty();
    }

    @Test
    @DisplayName("F2: event path after the scan queues no duplicate quantity for the same breach")
    void eventPathAfterScan_noDuplicateQuantityForSameBreach() {
        UUID skuId = UUID.randomUUID();
        String sku = skuId.toString();
        UUID location = UUID.randomUUID();
        seedPolicy(sku, location, 5, 20);
        receive(sku, location, 3);

        replenishmentService.runBatchReplenishmentScan();
        var eventResponse = replenishmentService.evaluatePickFaceForReplenishment(sku, location);

        // The scan's open task is the in-progress supply for this breach: the event path
        // must short-circuit instead of adding quantity.
        assertThat(eventResponse.getStatus()).isEqualTo("TASK_ALREADY_QUEUED");
        List<ReplenishmentTask> tasks = openTasksFor(sku);
        assertThat(tasks).hasSize(1);
        assertThat(tasks.getFirst().getQuantity()).isEqualTo(17); // total open quantity unchanged
    }

    private void savePo(
            PurchaseOrderStatus status, UUID shipToSiteId, LocalDate expectedDeliveryDate, UUID skuId, String openQty) {
        BigDecimal open = new BigDecimal(openQty);
        PurchaseOrderEntity po = PurchaseOrderEntity.builder()
                .vendorId(UUID.randomUUID())
                .poNumber("F2-" + PO_SEQ.incrementAndGet() + "-" + UUID.randomUUID())
                .status(status)
                .currency("USD")
                .subtotalMinor(100L)
                .taxMinor(0L)
                .grandTotalMinor(100L)
                .shipToLocationId(shipToSiteId)
                .expectedDeliveryDate(expectedDeliveryDate)
                .build();
        PurchaseOrderLineEntity line = PurchaseOrderLineEntity.builder()
                .lineNumber(1)
                .skuId(skuId)
                .description("F2 orderpoint test line")
                .quantityDecimal(open.max(BigDecimal.ONE))
                .unitCostMinor(100L)
                .lineTotalMinor(100L)
                .openQuantityDecimal(open)
                .build();
        line.setPurchaseOrder(po);
        po.getLines().add(line);
        purchaseOrderRepository.save(po);
    }
}

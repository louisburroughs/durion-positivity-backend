package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.inventory.internal.dto.replenishment.ReplenishmentScanResultResponse;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.ReplenishmentPolicy;
import com.positivity.inventory.internal.entity.ReplenishmentTask;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.ReplenishmentStatus;
import com.positivity.inventory.internal.enums.ReplenishmentTriggerType;
import com.positivity.inventory.internal.repository.ReplenishmentPolicyRepository;
import com.positivity.inventory.internal.repository.ReplenishmentTaskRepository;
import com.positivity.inventory.service.ReplenishmentService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * End-to-end tests for the batch replenishment scan (CAP-217 / odoo-parity F1,
 * issue #1025) against the real repositories and the {@code
 * inventory_stock_summary} read model maintained by the ledger posting funnel.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Batch replenishment scan (F1)")
class BatchReplenishmentScanTest {

    @Autowired
    private ReplenishmentService replenishmentService;

    @Autowired
    private ReplenishmentPolicyRepository policyRepository;

    @Autowired
    private ReplenishmentTaskRepository taskRepository;

    @Autowired
    private LedgerPostingService ledgerPostingService;

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
}

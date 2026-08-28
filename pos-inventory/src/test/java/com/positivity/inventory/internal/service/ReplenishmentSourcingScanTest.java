package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.inventory.internal.dto.transfer.TransferOrderLineResponse;
import com.positivity.inventory.internal.dto.transfer.TransferOrderResponse;
import com.positivity.inventory.internal.entity.ExtStorageLocationReplica;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.LocationRefEntity;
import com.positivity.inventory.internal.entity.NormalizedAvailability;
import com.positivity.inventory.internal.entity.ReplenishmentPolicy;
import com.positivity.inventory.internal.entity.ReplenishmentTask;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.ReplenishmentDecisionReason;
import com.positivity.inventory.internal.enums.ReplenishmentSourceType;
import com.positivity.inventory.internal.enums.ReplenishmentSourcingReason;
import com.positivity.inventory.internal.enums.ReplenishmentStatus;
import com.positivity.inventory.internal.enums.TransferOrderStatus;
import com.positivity.inventory.internal.replenishment.service.ReplenishmentService;
import com.positivity.inventory.internal.repository.NormalizedAvailabilityRepository;
import com.positivity.inventory.internal.repository.PurchaseSuggestionRepository;
import com.positivity.inventory.internal.repository.ReplenishmentPolicyRepository;
import com.positivity.inventory.internal.repository.ReplenishmentTaskRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * End-to-end tests for F5 internal sourcing resolution (odoo-parity F5, issue #1045) against
 * the real sourcing engine (H1), transfer-order lifecycle (C1), stock summary read model, and
 * vendor feeds. Exercises the three source-type branches, surplus/own-min candidate math, the
 * cross-site transfer materialization + linkage, and the re-sourcing idempotency guard.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Replenishment internal sourcing scan (F5)")
class ReplenishmentSourcingScanTest {

    private static final String ACTOR = "f5-test";

    @Autowired
    private ReplenishmentService replenishmentService;

    @Autowired
    private ReplenishmentPolicyRepository policyRepository;

    @Autowired
    private ReplenishmentTaskRepository taskRepository;

    @Autowired
    private com.positivity.inventory.internal.movement.service.TransferOrderService transferOrderService;

    @Autowired
    private PurchaseSuggestionRepository suggestionRepository;

    @Autowired
    private NormalizedAvailabilityRepository normalizedAvailabilityRepository;

    @Autowired
    private com.positivity.inventory.internal.repository.LocationRefRepository locationRefRepository;

    @Autowired
    private com.positivity.inventory.internal.repository.ExtStorageLocationReplicaRepository
            storageLocationReplicaRepository;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    // ─── seeding helpers ─────────────────────────────────────────────────────

    private static String uniqueSku() {
        return "SKU-F5-" + UUID.randomUUID();
    }

    private void receive(String sku, UUID locationId, BigDecimal quantity) {
        ledgerPostingService.post(InventoryLedgerEntry.builder()
                .stockItemId(sku)
                .locationId(locationId)
                .eventType(InventoryLedgerEventType.GOODS_RECEIPT)
                .changeInQuantity(quantity)
                .quantityAfter(quantity)
                .transactionUserId(ACTOR)
                .build());
    }

    private ReplenishmentPolicy seedPolicy(
            String sku, UUID locationId, int min, int max, ReplenishmentSourceType type) {
        return policyRepository.save(ReplenishmentPolicy.builder()
                .locationId(locationId)
                .itemSKU(sku)
                .minimumQuantity(min)
                .maximumQuantity(max)
                .preferredSourceType(type)
                .build());
    }

    private UUID seedActiveSite() {
        UUID siteId = UUID.randomUUID();
        locationRefRepository.save(LocationRefEntity.builder()
                .locationId(siteId)
                .name("F5 site " + siteId)
                .status("ACTIVE")
                .active(true)
                .aggregateVersion(0)
                .build());
        return siteId;
    }

    private UUID seedBin(UUID siteId) {
        UUID binId = UUID.randomUUID();
        storageLocationReplicaRepository.save(ExtStorageLocationReplica.builder()
                .storageLocationId(binId)
                .siteId(siteId)
                .name("F5 bin " + binId)
                .build());
        return binId;
    }

    private List<ReplenishmentTask> tasksFor(String sku) {
        return taskRepository.findAll().stream()
                .filter(task -> task.getItemSKU().equals(sku))
                .toList();
    }

    private List<TransferOrderResponse> transfersFor(String sku) {
        return transferOrderService.listTransferOrders(null, null, null).stream()
                .filter(order -> order.getLines() != null
                        && order.getLines().stream().anyMatch(line -> sku.equals(line.getSku())))
                .toList();
    }

    // ─── tests ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("INTERNAL_TRANSFER with a surplus sibling site materializes a linked cross-site TransferOrder")
    void internalTransfer_siblingSurplus_createsLinkedTransfer() {
        String sku = uniqueSku();
        UUID destSite = seedActiveSite();
        UUID srcSite = seedActiveSite();
        seedPolicy(sku, destSite, 5, 20, ReplenishmentSourceType.INTERNAL_TRANSFER);
        receive(sku, destSite, new BigDecimal("3")); // pick-face on-hand 3 < min 5 → need 17
        receive(sku, srcSite, new BigDecimal("30")); // sibling surplus 30 covers 17

        replenishmentService.runBatchReplenishmentScan();

        List<TransferOrderResponse> transfers = transfersFor(sku);
        assertThat(transfers).hasSize(1);
        TransferOrderResponse transfer = transfers.getFirst();
        assertThat(transfer.getStatus()).isEqualTo(TransferOrderStatus.DRAFT);
        assertThat(transfer.getSourceLocationId()).isEqualTo(srcSite);
        assertThat(transfer.getDestinationLocationId()).isEqualTo(destSite);
        assertThat(transfer.getLines()).hasSize(1);
        TransferOrderLineResponse line = transfer.getLines().getFirst();
        assertThat(line.getSku()).isEqualTo(sku);
        assertThat(line.getRequestedQty()).isEqualTo(17);
        assertThat(transfer.getTransferOrderId()).isNotNull();

        List<ReplenishmentTask> tasks = tasksFor(sku);
        assertThat(tasks).hasSize(1);
        ReplenishmentTask task = tasks.getFirst();
        assertThat(task.getSourceTransferOrderId()).isEqualTo(transfer.getTransferOrderId());
        assertThat(task.getSourceLocationId()).isEqualTo(srcSite);
        assertThat(task.getDestinationLocationId()).isEqualTo(destSite);
        assertThat(task.getQuantity()).isEqualTo(17);
        assertThat(task.getDecisionReason()).isEqualTo(ReplenishmentDecisionReason.BELOW_MIN);
        assertThat(task.getSourcingReason()).isEqualTo(ReplenishmentSourcingReason.FIFO);
    }

    @Test
    @DisplayName("A sibling source whose surplus is consumed by its OWN policy minimum is excluded from candidates")
    void siblingBelowOwnMin_excludedFromCandidates() {
        String sku = uniqueSku();
        UUID destSite = seedActiveSite();
        UUID srcSite = seedActiveSite();
        seedPolicy(sku, destSite, 5, 20, ReplenishmentSourceType.INTERNAL_TRANSFER);
        receive(sku, destSite, new BigDecimal("3"));
        receive(sku, srcSite, new BigDecimal("20")); // 20 on-hand, but the source keeps all 20 as its own floor
        // Source's own policy min = 20 → offerable surplus = 20 - 0 - 20 = 0 → not a candidate.
        // (min == on-hand so the source policy itself does not trigger: projected 20 !< 20.)
        seedPolicy(sku, srcSite, 20, 40, ReplenishmentSourceType.INTERNAL_TRANSFER);

        replenishmentService.runBatchReplenishmentScan();

        assertThat(transfersFor(sku)).isEmpty();
        List<ReplenishmentTask> tasks = tasksFor(sku);
        assertThat(tasks).hasSize(1);
        assertThat(tasks.getFirst().getDecisionReason()).isEqualTo(ReplenishmentDecisionReason.BACKSTOCK_UNAVAILABLE);
        assertThat(tasks.getFirst().getSourceTransferOrderId()).isNull();
    }

    @Test
    @DisplayName("EITHER with no internal surplus but a selectable vendor creates a purchase suggestion, not a task")
    void either_noInternalSurplus_withVendorFeed_createsSuggestion() {
        UUID productId = UUID.randomUUID();
        String sku = productId.toString();
        UUID destSite = seedActiveSite();
        ReplenishmentPolicy policy = seedPolicy(sku, destSite, 5, 20, ReplenishmentSourceType.EITHER);
        receive(sku, destSite, new BigDecimal("3")); // below min, no sibling surplus anywhere
        seedManufacturerFeed(productId, 50);

        replenishmentService.runBatchReplenishmentScan();

        assertThat(tasksFor(sku)).isEmpty();
        assertThat(transfersFor(sku)).isEmpty();
        assertThat(suggestionRepository.findFirstByPolicyIdAndStatusInOrderByCreatedAtAsc(
                        policy.getPolicyId(),
                        List.of(
                                com.positivity.inventory.internal.enums.PurchaseSuggestionStatus.SUGGESTED,
                                com.positivity.inventory.internal.enums.PurchaseSuggestionStatus.ACCEPTED)))
                .isPresent();
    }

    @Test
    @DisplayName("EITHER with an internal surplus prefers the transfer and creates no purchase suggestion")
    void either_internalSurplus_prefersTransferNotSuggestion() {
        UUID productId = UUID.randomUUID();
        String sku = productId.toString();
        UUID destSite = seedActiveSite();
        UUID srcSite = seedActiveSite();
        ReplenishmentPolicy policy = seedPolicy(sku, destSite, 5, 20, ReplenishmentSourceType.EITHER);
        receive(sku, destSite, new BigDecimal("3"));
        receive(sku, srcSite, new BigDecimal("30"));
        seedManufacturerFeed(productId, 50); // a vendor exists, but internal surplus wins

        replenishmentService.runBatchReplenishmentScan();

        assertThat(transfersFor(sku)).hasSize(1);
        assertThat(suggestionRepository.findFirstByPolicyIdAndStatusInOrderByCreatedAtAsc(
                        policy.getPolicyId(),
                        List.of(
                                com.positivity.inventory.internal.enums.PurchaseSuggestionStatus.SUGGESTED,
                                com.positivity.inventory.internal.enums.PurchaseSuggestionStatus.ACCEPTED)))
                .isEmpty();
    }

    @Test
    @DisplayName("Same-site backstock surplus stays a bin-move ReplenishmentTask, not a transfer")
    void sameSiteSurplus_isBinMoveTask_notTransfer() {
        String sku = uniqueSku();
        UUID destSite = seedActiveSite();
        UUID pickFace = seedBin(destSite);
        UUID backstock = seedBin(destSite);
        seedPolicy(sku, pickFace, 5, 20, ReplenishmentSourceType.INTERNAL_TRANSFER);
        receive(sku, pickFace, new BigDecimal("3")); // pick face below min → need 17
        receive(sku, backstock, new BigDecimal("30")); // same-site backstock surplus

        replenishmentService.runBatchReplenishmentScan();

        assertThat(transfersFor(sku)).isEmpty();
        List<ReplenishmentTask> tasks = tasksFor(sku);
        assertThat(tasks).hasSize(1);
        ReplenishmentTask task = tasks.getFirst();
        assertThat(task.getSourceLocationId()).isEqualTo(backstock);
        assertThat(task.getDestinationLocationId()).isEqualTo(pickFace);
        assertThat(task.getSourceTransferOrderId()).isNull();
        assertThat(task.getDecisionReason()).isEqualTo(ReplenishmentDecisionReason.BELOW_MIN);
        assertThat(task.getSourcingReason()).isNotNull();
    }

    @Test
    @DisplayName("An open source transfer suppresses re-sourcing the same need on the next scan")
    void openSourceTransfer_suppressesReSourcing() {
        String sku = uniqueSku();
        UUID destSite = seedActiveSite();
        UUID srcSite = seedActiveSite();
        seedPolicy(sku, destSite, 5, 20, ReplenishmentSourceType.INTERNAL_TRANSFER);
        receive(sku, destSite, new BigDecimal("3"));
        receive(sku, srcSite, new BigDecimal("30"));

        replenishmentService.runBatchReplenishmentScan();
        assertThat(transfersFor(sku)).hasSize(1);

        // Simulate the linked task having been worked/closed so ONLY the open-transfer guard
        // can suppress re-sourcing on the next pass.
        List<ReplenishmentTask> tasks = tasksFor(sku);
        assertThat(tasks).hasSize(1);
        ReplenishmentTask task = tasks.getFirst();
        task.setStatus(ReplenishmentStatus.COMPLETED);
        taskRepository.save(task);

        replenishmentService.runBatchReplenishmentScan();

        // No second transfer and no new task — the open DRAFT transfer covers the need.
        assertThat(transfersFor(sku)).hasSize(1);
        assertThat(tasksFor(sku)).hasSize(1);
    }

    @Test
    @DisplayName("INTERNAL_TRANSFER with nothing available anywhere yields a BACKSTOCK_UNAVAILABLE task")
    void internalTransfer_nothingAvailable_backstockUnavailableTask() {
        String sku = uniqueSku();
        UUID destSite = seedActiveSite();
        seedPolicy(sku, destSite, 5, 20, ReplenishmentSourceType.INTERNAL_TRANSFER);
        receive(sku, destSite, new BigDecimal("3")); // below min, no surplus anywhere

        replenishmentService.runBatchReplenishmentScan();

        assertThat(transfersFor(sku)).isEmpty();
        List<ReplenishmentTask> tasks = tasksFor(sku);
        assertThat(tasks).hasSize(1);
        ReplenishmentTask task = tasks.getFirst();
        assertThat(task.getDecisionReason()).isEqualTo(ReplenishmentDecisionReason.BACKSTOCK_UNAVAILABLE);
        assertThat(task.getSourceLocationId()).isEqualTo(destSite);
        assertThat(task.getQuantity()).isEqualTo(17);
        assertThat(task.getSourceTransferOrderId()).isNull();
    }

    private void seedManufacturerFeed(UUID productId, int availableQty) {
        Instant asOf = Instant.now();
        normalizedAvailabilityRepository.save(NormalizedAvailability.builder()
                .productId(productId)
                .manufacturerId(UUID.randomUUID())
                .manufacturerPartNumber("MPN-F5")
                .availableQty(availableQty)
                .uom("EA")
                .unitPriceAmount(new BigDecimal("1.00"))
                .unitPriceCurrency("USD")
                .leadTimeDaysMax(3)
                .packSize(1)
                .asOf(asOf)
                .receivedAt(asOf)
                .schemaVersion(1)
                .build());
    }
}

package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.inventory.internal.dto.replenishment.CreateReplenishmentPolicyRequest;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentNeedResponse;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentPolicyResponse;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentScanResultResponse;
import com.positivity.inventory.internal.dto.replenishment.UpdateReplenishmentPolicyRequest;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.NormalizedAvailability;
import com.positivity.inventory.internal.entity.PurchaseOrderEntity;
import com.positivity.inventory.internal.entity.PurchaseOrderLineEntity;
import com.positivity.inventory.internal.entity.ReplenishmentPolicy;
import com.positivity.inventory.internal.entity.ReplenishmentTask;
import com.positivity.inventory.internal.entity.ReservationEntity;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.PurchaseOrderStatus;
import com.positivity.inventory.internal.enums.ReplenishmentSourceType;
import com.positivity.inventory.internal.enums.ReplenishmentStatus;
import com.positivity.inventory.internal.enums.ReservationStatus;
import com.positivity.inventory.internal.exception.SnoozeUntilNotInFutureException;
import com.positivity.inventory.internal.repository.NormalizedAvailabilityRepository;
import com.positivity.inventory.internal.repository.ReplenishmentPolicyRepository;
import com.positivity.inventory.internal.repository.ReplenishmentTaskRepository;
import com.positivity.inventory.internal.repository.ReservationRepository;
import com.positivity.inventory.service.ReplenishmentService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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
 * End-to-end tests for the F3 policy enrichment (odoo-parity F3/F6, issue #1041):
 * order-multiple rounding, lead-time override precedence, snooze/active skips, the
 * stock-out deadline approximation, the side-effect-free needs report, and the
 * vendor-feed pack-size seed — against the real repositories and forecast math.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Replenishment policy enrichment (F3)")
class ReplenishmentPolicyEnrichmentTest {

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
    private ReservationRepository reservationRepository;

    @Autowired
    private NormalizedAvailabilityRepository normalizedAvailabilityRepository;

    @Autowired
    private com.positivity.inventory.internal.repository.PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private LeadTimeResolver leadTimeResolver;

    private static String uniqueSku() {
        return "SKU-F3-" + UUID.randomUUID();
    }

    private void receive(String sku, UUID locationId, int quantity) {
        ledgerPostingService.post(InventoryLedgerEntry.builder()
                .stockItemId(sku)
                .locationId(locationId)
                .eventType(InventoryLedgerEventType.GOODS_RECEIPT)
                .changeInQuantity(quantity)
                .quantityAfter(quantity)
                .transactionUserId("f3-test")
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

    // ─── Order-multiple rounding ─────────────────────────────────────────────

    @Test
    @DisplayName("need 7 with orderMultiple 6 rounds up to 12")
    void orderMultiple_roundsNeedUpToNearestMultiple() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();
        ReplenishmentPolicy policy = seedPolicy(sku, location, 5, 10);
        policy.setOrderMultiple(6);
        policyRepository.save(policy);
        receive(sku, location, 3); // need = 10 - 3 = 7 → rounded to 12

        replenishmentService.runBatchReplenishmentScan();

        List<ReplenishmentTask> tasks = openTasksFor(sku);
        assertThat(tasks).hasSize(1);
        assertThat(tasks.getFirst().getQuantity()).isEqualTo(12);
    }

    @Test
    @DisplayName("need that is already an exact multiple stays as-is")
    void orderMultiple_exactMultipleStaysUnchanged() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();
        ReplenishmentPolicy policy = seedPolicy(sku, location, 10, 20);
        policy.setOrderMultiple(6);
        policyRepository.save(policy);
        receive(sku, location, 8); // need = 20 - 8 = 12, exact multiple of 6

        replenishmentService.runBatchReplenishmentScan();

        List<ReplenishmentTask> tasks = openTasksFor(sku);
        assertThat(tasks).hasSize(1);
        assertThat(tasks.getFirst().getQuantity()).isEqualTo(12);
    }

    @Test
    @DisplayName("zero need stays zero despite an order multiple (no phantom round-up)")
    void orderMultiple_zeroNeedStaysZero() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();
        ReplenishmentPolicy policy = seedPolicy(sku, location, 5, 20);
        policy.setOrderMultiple(6);
        policyRepository.save(policy);
        receive(sku, location, 3);

        // Scan creates the open task (need 17 → rounded to 18); its quantity is now the
        // in-progress supply covering the breach.
        replenishmentService.runBatchReplenishmentScan();
        assertThat(openTasksFor(sku).getFirst().getQuantity()).isEqualTo(18);

        // Needs report nets that open task: 20 - 3 - 18 < 0 → suggested quantity is zero,
        // NOT rounded up to 6.
        ReplenishmentNeedResponse need = needFor(sku);
        assertThat(need.isWouldTrigger()).isTrue();
        assertThat(need.getSuggestedQuantity()).isZero();
    }

    // ─── Lead-time override precedence (D-4) ─────────────────────────────────

    @Test
    @DisplayName("policy leadTimeDaysOverride wins over vendor-feed lead time")
    void leadTimeOverride_winsOverFeed() {
        UUID skuId = UUID.randomUUID();
        String sku = skuId.toString();
        UUID location = UUID.randomUUID();
        // Feed lead time of 5 days would pull tomorrow's PO inside the horizon.
        seedFeedRow(skuId, 5, 1);
        ReplenishmentPolicy policy = seedPolicy(sku, location, 5, 20);
        policy.setLeadTimeDaysOverride(0);
        // Pin to INTERNAL_TRANSFER so this lead-time-precedence vector always materializes a
        // task: the seeded feed row (present only to supply a vendor lead time) would otherwise
        // make the default EITHER policy route to an F5 purchase suggestion (issue #1045),
        // which is exercised separately in ReplenishmentSourcingScanTest.
        policy.setPreferredSourceType(ReplenishmentSourceType.INTERNAL_TRANSFER);
        policyRepository.save(policy);
        receive(sku, location, 3);
        savePo(
                PurchaseOrderStatus.APPROVED,
                location,
                LocalDate.now(ZoneOffset.UTC).plusDays(1),
                skuId,
                "7");

        assertThat(leadTimeResolver.resolve(policy).source())
                .isEqualTo(LeadTimeResolver.LeadTimeSource.POLICY_OVERRIDE);
        assertThat(leadTimeResolver.resolve(policy).days()).isZero();

        replenishmentService.runBatchReplenishmentScan();

        // Override 0 puts tomorrow's PO OUTSIDE the horizon: projection stays 3 < min 5 →
        // task created off the projection. With the feed's 5 days it would not have fired.
        List<ReplenishmentTask> tasks = openTasksFor(sku);
        assertThat(tasks).hasSize(1);
        assertThat(tasks.getFirst().getQuantity()).isEqualTo(17);
    }

    // ─── Snooze / active skips ───────────────────────────────────────────────

    @Test
    @DisplayName("snoozed policy is skipped by scan and event path, and counted in the scan summary")
    void snoozedPolicy_isSkipped() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();
        ReplenishmentPolicy policy = seedPolicy(sku, location, 5, 20);
        policy.setSnoozedUntil(Instant.now().plus(Duration.ofHours(1)));
        policyRepository.save(policy);
        receive(sku, location, 3); // below min — would trigger if not snoozed

        ReplenishmentScanResultResponse result = replenishmentService.runBatchReplenishmentScan();

        assertThat(openTasksFor(sku)).isEmpty();
        assertThat(result.getPoliciesSkipped()).isPositive();
        assertThat(replenishmentService
                        .evaluatePickFaceForReplenishment(sku, location)
                        .getStatus())
                .isEqualTo("NO_ACTION");
        assertThat(needFor(sku)).isNull(); // excluded from the needs report too
    }

    @Test
    @DisplayName("policy resumes automatically once the snooze instant has passed")
    void expiredSnooze_resumesEvaluation() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();
        ReplenishmentPolicy policy = seedPolicy(sku, location, 5, 20);
        policy.setSnoozedUntil(Instant.now().minus(Duration.ofSeconds(1)));
        policyRepository.save(policy);
        receive(sku, location, 3);

        replenishmentService.runBatchReplenishmentScan();

        assertThat(openTasksFor(sku)).hasSize(1); // past snooze instant = no suppression
    }

    @Test
    @DisplayName("inactive policy is skipped even when below minimum")
    void inactivePolicy_isSkipped() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();
        ReplenishmentPolicy policy = seedPolicy(sku, location, 5, 20);
        policy.setActive(Boolean.FALSE);
        policyRepository.save(policy);
        receive(sku, location, 3);

        ReplenishmentScanResultResponse result = replenishmentService.runBatchReplenishmentScan();

        assertThat(openTasksFor(sku)).isEmpty();
        assertThat(result.getPoliciesSkipped()).isPositive();
        assertThat(needFor(sku)).isNull();
    }

    // ─── Snooze endpoint semantics ───────────────────────────────────────────

    @Test
    @DisplayName("snooze requires a future instant (422 semantics) and null clears the snooze")
    void snooze_validatesFutureAndNullClears() {
        String sku = uniqueSku();
        ReplenishmentPolicy policy = seedPolicy(sku, UUID.randomUUID(), 5, 20);
        UUID policyId = policy.getPolicyId();
        Instant future = Instant.now().plus(Duration.ofDays(1));

        assertThatThrownBy(() -> replenishmentService.snoozeReplenishmentPolicy(
                        policyId, Instant.now().minus(Duration.ofHours(1))))
                .isInstanceOf(SnoozeUntilNotInFutureException.class);

        ReplenishmentPolicyResponse snoozed = replenishmentService.snoozeReplenishmentPolicy(policyId, future);
        assertThat(snoozed.getSnoozedUntil()).isEqualTo(future.toString());

        ReplenishmentPolicyResponse cleared = replenishmentService.snoozeReplenishmentPolicy(policyId, null);
        assertThat(cleared.getSnoozedUntil()).isNull();
    }

    // ─── Update endpoint semantics ───────────────────────────────────────────

    @Test
    @DisplayName("PUT update replaces thresholds and tuning fields; omitted optionals reset to defaults")
    void update_fullReplaceSemantics() {
        String sku = uniqueSku();
        ReplenishmentPolicy policy = seedPolicy(sku, UUID.randomUUID(), 5, 20);
        UUID policyId = policy.getPolicyId();

        ReplenishmentPolicyResponse updated = replenishmentService.updateReplenishmentPolicy(
                policyId,
                UpdateReplenishmentPolicyRequest.builder()
                        .minimumQuantity(8)
                        .maximumQuantity(40)
                        .orderMultiple(6)
                        .leadTimeDaysOverride(3)
                        .preferredSourceType(ReplenishmentSourceType.PURCHASE)
                        .active(Boolean.FALSE)
                        .build());
        assertThat(updated.getMinimumQuantity()).isEqualTo(8);
        assertThat(updated.getMaximumQuantity()).isEqualTo(40);
        assertThat(updated.getOrderMultiple()).isEqualTo(6);
        assertThat(updated.getLeadTimeDaysOverride()).isEqualTo(3);
        assertThat(updated.getPreferredSourceType()).isEqualTo("PURCHASE");
        assertThat(updated.isActive()).isFalse();

        // PUT again with only the required fields: optionals clear / reset to defaults.
        ReplenishmentPolicyResponse reset = replenishmentService.updateReplenishmentPolicy(
                policyId,
                UpdateReplenishmentPolicyRequest.builder()
                        .minimumQuantity(8)
                        .maximumQuantity(40)
                        .build());
        assertThat(reset.getOrderMultiple()).isNull();
        assertThat(reset.getLeadTimeDaysOverride()).isNull();
        assertThat(reset.getPreferredSourceType()).isEqualTo("EITHER");
        assertThat(reset.isActive()).isTrue();
    }

    // ─── Pack-size seed on creation ──────────────────────────────────────────

    @Test
    @DisplayName("orderMultiple defaults to the vendor-feed pack size when omitted at creation")
    void create_seedsOrderMultipleFromFeedPackSize() {
        UUID skuId = UUID.randomUUID();
        seedFeedRow(skuId, null, 6);

        ReplenishmentPolicyResponse seeded =
                replenishmentService.createReplenishmentPolicy(createRequest(skuId.toString(), null));
        assertThat(seeded.getOrderMultiple()).isEqualTo(6);

        // An explicit orderMultiple always wins over the feed seed.
        ReplenishmentPolicyResponse explicit =
                replenishmentService.createReplenishmentPolicy(createRequest(skuId.toString(), 4));
        assertThat(explicit.getOrderMultiple()).isEqualTo(4);

        // Non-UUID SKUs have no feed identity: no seed, no rounding.
        ReplenishmentPolicyResponse plain =
                replenishmentService.createReplenishmentPolicy(createRequest(uniqueSku(), null));
        assertThat(plain.getOrderMultiple()).isNull();
    }

    // ─── Needs report (F6) ───────────────────────────────────────────────────

    @Test
    @DisplayName("needs report matches the subsequent scan's output and creates no tasks")
    void needsReport_matchesScanAndMutatesNothing() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();
        ReplenishmentPolicy policy = seedPolicy(sku, location, 5, 10);
        policy.setOrderMultiple(6);
        policyRepository.save(policy);
        receive(sku, location, 3); // need 7 → rounded 12

        long tasksBefore = taskRepository.count();
        ReplenishmentNeedResponse need = needFor(sku);
        assertThat(taskRepository.count()).isEqualTo(tasksBefore); // side-effect free

        assertThat(need.isWouldTrigger()).isTrue();
        assertThat(need.getOnHand()).isEqualTo(3);
        assertThat(need.getProjectedAvailable()).isEqualTo(3);
        assertThat(need.getSuggestedQuantity()).isEqualTo(12);
        assertThat(need.getMinimumQuantity()).isEqualTo(5);
        assertThat(need.getMaximumQuantity()).isEqualTo(10);
        assertThat(need.getPreferredSourceType()).isEqualTo("EITHER");
        assertThat(need.getLeadTimeSource()).isEqualTo("DEFAULT");

        replenishmentService.runBatchReplenishmentScan();

        List<ReplenishmentTask> tasks = openTasksFor(sku);
        assertThat(tasks).hasSize(1);
        assertThat(tasks.getFirst().getQuantity()).isEqualTo(need.getSuggestedQuantity());
    }

    // ─── Stock-out deadline ──────────────────────────────────────────────────

    @Test
    @DisplayName("deadline is the demand due date at which the projection first goes negative")
    void deadline_isFirstNegativeCutoffDate() {
        UUID skuId = UUID.randomUUID();
        String sku = skuId.toString();
        UUID location = UUID.randomUUID();
        ReplenishmentPolicy policy = seedPolicy(sku, location, 10, 20);
        policy.setLeadTimeDaysOverride(5);
        policyRepository.save(policy);
        receive(sku, location, 5);
        // Open reservation remainder of 8 due in 2 days: projected(now) = 5 >= 0, but at
        // the due-date cutoff the projection drops to 5 - 8 = -3 < 0 → deadline = due date.
        Instant due = Instant.now().plus(Duration.ofDays(2));
        reservationRepository.save(ReservationEntity.builder()
                .workorderLineId(UUID.randomUUID())
                .stockItemId(skuId)
                .requiredQuantity(13)
                .allocatedQuantity(5)
                .status(ReservationStatus.PENDING)
                .dueDateTime(due)
                .build());

        replenishmentService.runBatchReplenishmentScan();

        List<ReplenishmentTask> tasks = openTasksFor(sku);
        assertThat(tasks).hasSize(1);
        assertThat(tasks.getFirst().getDeadlineDate()).isEqualTo(LocalDate.ofInstant(due, ZoneOffset.UTC));

        ReplenishmentNeedResponse need = needFor(sku);
        assertThat(need.getDeadlineDate())
                .isEqualTo(LocalDate.ofInstant(due, ZoneOffset.UTC).toString());
        assertThat(need.getLeadTimeSource()).isEqualTo("POLICY_OVERRIDE");
    }

    @Test
    @DisplayName("deadline is today when the projection is already negative now")
    void deadline_isTodayWhenAlreadyNegative() {
        UUID skuId = UUID.randomUUID();
        String sku = skuId.toString();
        UUID location = UUID.randomUUID();
        seedPolicy(sku, location, 5, 20);
        receive(sku, location, 2);
        // Overdue reservation remainder of 5: projected(now) = 2 - 5 = -3 < 0 → deadline today.
        reservationRepository.save(ReservationEntity.builder()
                .workorderLineId(UUID.randomUUID())
                .stockItemId(skuId)
                .requiredQuantity(5)
                .allocatedQuantity(0)
                .status(ReservationStatus.PENDING)
                .dueDateTime(Instant.now().minus(Duration.ofHours(1)))
                .build());

        replenishmentService.runBatchReplenishmentScan();

        List<ReplenishmentTask> tasks = openTasksFor(sku);
        assertThat(tasks).hasSize(1);
        assertThat(tasks.getFirst().getDeadlineDate()).isEqualTo(LocalDate.now(ZoneOffset.UTC));
    }

    @Test
    @DisplayName("no deadline when the projection never goes below zero within the horizon")
    void deadline_absentWhenNoProjectedStockout() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();
        seedPolicy(sku, location, 5, 20);
        receive(sku, location, 3); // below min but never below zero

        replenishmentService.runBatchReplenishmentScan();

        List<ReplenishmentTask> tasks = openTasksFor(sku);
        assertThat(tasks).hasSize(1);
        assertThat(tasks.getFirst().getDeadlineDate()).isNull();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private ReplenishmentNeedResponse needFor(String sku) {
        return replenishmentService.getReplenishmentNeeds().stream()
                .filter(need -> sku.equals(need.getItemSKU()))
                .findFirst()
                .orElse(null);
    }

    private CreateReplenishmentPolicyRequest createRequest(String sku, Integer orderMultiple) {
        CreateReplenishmentPolicyRequest request = new CreateReplenishmentPolicyRequest();
        request.setLocationId(UUID.randomUUID());
        request.setItemSKU(sku);
        request.setMinimumQuantity(5);
        request.setMaximumQuantity(20);
        request.setOrderMultiple(orderMultiple);
        return request;
    }

    private void seedFeedRow(UUID productId, Integer leadTimeDaysMax, int packSize) {
        normalizedAvailabilityRepository.save(NormalizedAvailability.builder()
                .productId(productId)
                .manufacturerId(UUID.randomUUID())
                .manufacturerPartNumber("MPN-" + productId)
                .availableQty(100)
                .uom("EA")
                .leadTimeDaysMin(leadTimeDaysMax)
                .leadTimeDaysMax(leadTimeDaysMax)
                .minOrderQty(1)
                .packSize(packSize)
                .asOf(Instant.now())
                .receivedAt(Instant.now())
                .schemaVersion(1)
                .build());
    }

    private void savePo(
            PurchaseOrderStatus status, UUID shipToSiteId, LocalDate expectedDeliveryDate, UUID skuId, String openQty) {
        BigDecimal open = new BigDecimal(openQty);
        PurchaseOrderEntity po = PurchaseOrderEntity.builder()
                .vendorId(UUID.randomUUID())
                .poNumber("F3-" + PO_SEQ.incrementAndGet() + "-" + UUID.randomUUID())
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
                .description("F3 enrichment test line")
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

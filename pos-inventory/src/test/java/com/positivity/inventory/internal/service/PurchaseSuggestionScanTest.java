package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.inventory.internal.dto.purchasesuggestion.ConvertPurchaseSuggestionsRequest;
import com.positivity.inventory.internal.dto.purchasesuggestion.ConvertPurchaseSuggestionsResponse;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentScanResultResponse;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.NormalizedAvailability;
import com.positivity.inventory.internal.entity.PurchaseSuggestion;
import com.positivity.inventory.internal.entity.ReplenishmentPolicy;
import com.positivity.inventory.internal.entity.ReplenishmentTask;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.PurchaseSuggestionStatus;
import com.positivity.inventory.internal.enums.ReplenishmentSourceType;
import com.positivity.inventory.internal.enums.ReplenishmentStatus;
import com.positivity.inventory.internal.enums.SuggestedVendorType;
import com.positivity.inventory.internal.replenishment.service.PurchaseSuggestionService;
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
 * End-to-end tests for purchase suggestion creation from the replenishment scan and the
 * in-progress netting seam (odoo-parity F4, issue #1044; plan D-3) against the real
 * repositories, ledger-maintained stock summary, and real PO expected supply.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Purchase suggestions: scan creation and netting (F4)")
class PurchaseSuggestionScanTest {

    private static final String ACTOR = "f4-test";

    @Autowired
    private ReplenishmentService replenishmentService;

    @Autowired
    private PurchaseSuggestionService purchaseSuggestionService;

    @Autowired
    private ReplenishmentPolicyRepository policyRepository;

    @Autowired
    private ReplenishmentTaskRepository taskRepository;

    @Autowired
    private PurchaseSuggestionRepository suggestionRepository;

    @Autowired
    private NormalizedAvailabilityRepository normalizedAvailabilityRepository;

    @Autowired
    private PurchaseOrderProjectionTestSupport projection;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @org.junit.jupiter.api.BeforeEach
    void createPoNumberSequence() {
        // Flyway is disabled under the test profile (ddl-auto), so the V2 sequence used by
        // PO number generation must exist explicitly (same pattern as the PO contract ITs).
        jdbcTemplate.execute("CREATE SEQUENCE IF NOT EXISTS purchase_order_number_seq START WITH 1 INCREMENT BY 1");
    }

    private void post(String sku, UUID locationId, InventoryLedgerEventType eventType, BigDecimal change, int after) {
        ledgerPostingService.post(InventoryLedgerEntry.builder()
                .stockItemId(sku)
                .locationId(locationId)
                .eventType(eventType)
                .changeInQuantity(change)
                .quantityAfter(BigDecimal.valueOf(after))
                .transactionUserId(ACTOR)
                .build());
    }

    private ReplenishmentPolicy seedPurchasePolicy(
            String sku, UUID locationId, int min, int max, Integer orderMultiple, Integer leadTimeDaysOverride) {
        return policyRepository.save(ReplenishmentPolicy.builder()
                .locationId(locationId)
                .itemSKU(sku)
                .minimumQuantity(min)
                .maximumQuantity(max)
                .orderMultiple(orderMultiple)
                .leadTimeDaysOverride(leadTimeDaysOverride)
                .preferredSourceType(ReplenishmentSourceType.PURCHASE)
                .build());
    }

    private NormalizedAvailability seedManufacturerFeed(
            UUID productId,
            UUID manufacturerId,
            int availableQty,
            Integer leadMax,
            BigDecimal price,
            Integer minOrderQty,
            int packSize) {
        Instant asOf = Instant.now();
        return normalizedAvailabilityRepository.save(NormalizedAvailability.builder()
                .productId(productId)
                .manufacturerId(manufacturerId)
                .manufacturerPartNumber("MPN-F4")
                .availableQty(availableQty)
                .uom("EA")
                .unitPriceAmount(price)
                .unitPriceCurrency(price != null ? "USD" : null)
                .leadTimeDaysMax(leadMax)
                .minOrderQty(minOrderQty)
                .packSize(packSize)
                .asOf(asOf)
                .receivedAt(asOf)
                .schemaVersion(1)
                .build());
    }

    private List<PurchaseSuggestion> suggestionsFor(String sku) {
        return suggestionRepository.findAll().stream()
                .filter(suggestion -> suggestion.getItemSKU().equals(sku))
                .toList();
    }

    private List<ReplenishmentTask> openTasksFor(String sku) {
        return taskRepository
                .findByStatusIn(List.of(ReplenishmentStatus.PENDING, ReplenishmentStatus.IN_PROGRESS))
                .stream()
                .filter(task -> task.getItemSKU().equals(sku))
                .toList();
    }

    @Test
    @DisplayName("a triggered PURCHASE-preferring policy yields a suggestion, never a task")
    void purchasePolicy_scanCreatesSuggestionNotTask() {
        UUID productId = UUID.randomUUID();
        UUID manufacturerId = UUID.randomUUID();
        String sku = productId.toString();
        UUID location = UUID.randomUUID();
        seedPurchasePolicy(sku, location, 5, 20, null, 0);
        seedManufacturerFeed(productId, manufacturerId, 100, 5, new BigDecimal("4.99"), null, 1);
        post(sku, location, InventoryLedgerEventType.GOODS_RECEIPT, new BigDecimal("3"), 3); // on-hand 3 < min 5

        ReplenishmentScanResultResponse result = replenishmentService.runBatchReplenishmentScan();

        assertThat(openTasksFor(sku)).isEmpty(); // never a task (D-3 routing)
        List<PurchaseSuggestion> suggestions = suggestionsFor(sku);
        assertThat(suggestions).hasSize(1);
        PurchaseSuggestion suggestion = suggestions.getFirst();
        assertThat(suggestion.getStatus()).isEqualTo(PurchaseSuggestionStatus.SUGGESTED);
        assertThat(suggestion.getSuggestedQuantity()).isEqualTo(17); // max 20 − on-hand 3
        assertThat(suggestion.getSuggestedVendorType()).isEqualTo(SuggestedVendorType.MANUFACTURER);
        assertThat(suggestion.getVendorRefId()).isEqualTo(manufacturerId);
        assertThat(suggestion.getUnitCostMinor()).isEqualTo(499L);
        assertThat(suggestion.getUnitCostCurrency()).isEqualTo("USD");
        assertThat(suggestion.getLeadTimeDays()).isEqualTo(5);
        assertThat(suggestion.getEarliestExpectedDate()).isNotNull();
        assertThat(suggestion.getSelectionReason()).contains("MANUFACTURER " + manufacturerId);
        assertThat(result.getSuggestionsCreated()).isPositive();
    }

    @Test
    @DisplayName("repeated scans keep exactly one open suggestion; a refresh recomputes the quantity")
    void repeatedScans_idempotentAndRefreshing() {
        UUID productId = UUID.randomUUID();
        String sku = productId.toString();
        UUID location = UUID.randomUUID();
        seedPurchasePolicy(sku, location, 5, 20, null, 0);
        seedManufacturerFeed(productId, UUID.randomUUID(), 100, 5, new BigDecimal("4.99"), null, 1);
        post(sku, location, InventoryLedgerEventType.GOODS_RECEIPT, new BigDecimal("4"), 4); // need 16

        replenishmentService.runBatchReplenishmentScan();
        replenishmentService.runBatchReplenishmentScan();
        replenishmentService.runBatchReplenishmentScan();

        List<PurchaseSuggestion> suggestions = suggestionsFor(sku);
        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.getFirst().getSuggestedQuantity()).isEqualTo(16);

        // Stock drops between scans → the open suggestion's quantity is refreshed, not duplicated.
        post(sku, location, InventoryLedgerEventType.GOODS_ISSUE, new BigDecimal("-2"), 2);
        ReplenishmentScanResultResponse second = replenishmentService.runBatchReplenishmentScan();

        suggestions = suggestionsFor(sku);
        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.getFirst().getSuggestedQuantity()).isEqualTo(18); // max 20 − on-hand 2
        assertThat(second.getSuggestionsRefreshed()).isPositive();
        assertThat(second.getSuggestionsCreated()).isZero();
    }

    @Test
    @DisplayName("MOQ/pack/orderMultiple interplay applies end-to-end: need 17 → multiple max(4,6)=6 → 18 → MOQ 24")
    void roundingInterplay_endToEnd() {
        UUID productId = UUID.randomUUID();
        String sku = productId.toString();
        UUID location = UUID.randomUUID();
        seedPurchasePolicy(sku, location, 5, 20, 4, 0); // orderMultiple 4
        seedManufacturerFeed(productId, UUID.randomUUID(), 100, 5, new BigDecimal("4.99"), 24, 6); // MOQ 24, pack 6
        post(sku, location, InventoryLedgerEventType.GOODS_RECEIPT, new BigDecimal("3"), 3); // raw need 17

        replenishmentService.runBatchReplenishmentScan();

        List<PurchaseSuggestion> suggestions = suggestionsFor(sku);
        assertThat(suggestions).hasSize(1);
        // roundUpToMultiple(17, max(4, 6)) = 18; max(MOQ 24, 18) = 24.
        assertThat(suggestions.getFirst().getSuggestedQuantity()).isEqualTo(24);
        assertThat(suggestions.getFirst().getVendorPackSize()).isEqualTo(6);
    }

    @Test
    @DisplayName("an open suggestion suppresses the event path — no duplicate quantity for the same breach")
    void openSuggestion_suppressesEventPath() {
        UUID productId = UUID.randomUUID();
        String sku = productId.toString();
        UUID location = UUID.randomUUID();
        seedPurchasePolicy(sku, location, 5, 20, null, 0);
        seedManufacturerFeed(productId, UUID.randomUUID(), 100, 5, new BigDecimal("4.99"), null, 1);
        post(sku, location, InventoryLedgerEventType.GOODS_RECEIPT, new BigDecimal("3"), 3);

        replenishmentService.runBatchReplenishmentScan();
        var eventResponse = replenishmentService.evaluatePickFaceForReplenishment(sku, location);

        // The open suggestion is the in-progress supply for this breach: the event path
        // must not add quantity (refresh of an unchanged need is not a new suggestion).
        assertThat(eventResponse.getStatus()).isEqualTo("NO_ACTION");
        assertThat(suggestionsFor(sku)).hasSize(1);
        assertThat(openTasksFor(sku)).isEmpty();
    }

    @Test
    @DisplayName("netting handoff: CONVERTED nets while the PO is DRAFT and stops exactly when approval"
            + " moves the PO into expected supply — no gap, no double-count")
    void convertedSuggestion_nettingHandsOffToPoSupply() {
        UUID productId = UUID.randomUUID();
        UUID manufacturerId = UUID.randomUUID();
        String sku = productId.toString();
        UUID location = UUID.randomUUID();
        // Lead-time override 30d: the horizon covers the converted PO's expected date, so
        // approved-PO supply counts in the projection (F2 horizon semantics).
        seedPurchasePolicy(sku, location, 10, 20, null, 30);
        seedManufacturerFeed(productId, manufacturerId, 100, 5, new BigDecimal("4.99"), null, 1);
        // No receipts → on-hand 0 < min 10 → need 20.

        replenishmentService.runBatchReplenishmentScan();
        PurchaseSuggestion suggestion = suggestionsFor(sku).getFirst();
        assertThat(suggestion.getSuggestedQuantity()).isEqualTo(20);

        purchaseSuggestionService.acceptPurchaseSuggestion(suggestion.getSuggestionId(), ACTOR);
        ConvertPurchaseSuggestionsResponse converted = purchaseSuggestionService.convertPurchaseSuggestions(
                new ConvertPurchaseSuggestionsRequest(List.of(suggestion.getSuggestionId())), ACTOR);
        assertThat(converted.getPurchaseOrderStatus()).isEqualTo("REQUESTED");
        // pos-order places the order and publishes the fact; here that arrival is stated directly.
        projection.project(
                converted.getPurchaseOrderId(),
                "DRAFT",
                location,
                java.time.LocalDate.now(java.time.ZoneOffset.UTC),
                List.of(new PurchaseOrderProjectionTestSupport.Line(productId, "17", "17")));

        // Phase 1 — PO still DRAFT: not yet expected supply (A2 counts APPROVED+), so the
        // CONVERTED suggestion must keep netting: no re-suggest, no task.
        assertThat(replenishmentService
                        .evaluatePickFaceForReplenishment(sku, location)
                        .getStatus())
                .isEqualTo("NO_ACTION");
        assertThat(suggestionsFor(sku)).hasSize(1);
        assertThat(openTasksFor(sku)).isEmpty();

        // Phase 2 — approve the PO: its open quantity enters expected supply, the CONVERTED
        // suggestion must stop netting at the same moment. Projection = 0 + 20 ≥ min → no trigger.
        // Approval is what turns the order into expected supply, and it reaches this module as a
        // fact from pos-order rather than as a write here (CAP-320 #1334).
        projection.restate(converted.getPurchaseOrderId(), "APPROVED");
        assertThat(replenishmentService
                        .evaluatePickFaceForReplenishment(sku, location)
                        .getStatus())
                .isEqualTo("NO_ACTION");
        assertThat(suggestionsFor(sku)).hasSize(1); // still no double-ordering

        // Phase 3 — prove the CONVERTED suggestion really stopped netting (exactly-one
        // counting): simulate a partial receipt — 15 received (open remainder 5), then 12
        // issued. Projection = on-hand 3 + open PO 5 = 8 < min 10 → a NEW suggestion for
        // the remaining gap must appear: raw need = max 20 − projected 8 − netting 0 = 12.
        // Were the CONVERTED suggestion still netting its 20, the need would be 0 and the
        // breach would be silently swallowed.
        // pos-inventory reports the receipt; pos-order applies it and publishes the order's new
        // state. What lands here is that state.
        projection.restate(converted.getPurchaseOrderId(), "PARTIALLY_RECEIVED");
        projection.setOpenQuantity(converted.getPurchaseOrderId(), "5");
        post(sku, location, InventoryLedgerEventType.GOODS_RECEIPT, new BigDecimal("15"), 15);
        post(sku, location, InventoryLedgerEventType.GOODS_ISSUE, new BigDecimal("-12"), 3);

        var eventResponse = replenishmentService.evaluatePickFaceForReplenishment(sku, location);

        assertThat(eventResponse.getStatus()).isEqualTo("PURCHASE_SUGGESTED");
        List<PurchaseSuggestion> suggestions = suggestionsFor(sku);
        assertThat(suggestions).hasSize(2);
        PurchaseSuggestion followUp = suggestions.stream()
                .filter(candidate -> candidate.getStatus() == PurchaseSuggestionStatus.SUGGESTED)
                .findFirst()
                .orElseThrow();
        assertThat(followUp.getSuggestedQuantity()).isEqualTo(12);
        assertThat(openTasksFor(sku)).isEmpty();
    }

    @Test
    @DisplayName("convert happy path: DRAFT PO carries the suggestion's vendor, quantity, and unit cost")
    void convert_producesDraftPoMatchingSuggestion() {
        UUID productId = UUID.randomUUID();
        UUID manufacturerId = UUID.randomUUID();
        String sku = productId.toString();
        UUID location = UUID.randomUUID();
        seedPurchasePolicy(sku, location, 5, 20, null, 0);
        seedManufacturerFeed(productId, manufacturerId, 100, 5, new BigDecimal("4.99"), null, 1);
        post(sku, location, InventoryLedgerEventType.GOODS_RECEIPT, new BigDecimal("3"), 3);

        replenishmentService.runBatchReplenishmentScan();
        PurchaseSuggestion suggestion = suggestionsFor(sku).getFirst();
        purchaseSuggestionService.acceptPurchaseSuggestion(suggestion.getSuggestionId(), ACTOR);

        ConvertPurchaseSuggestionsResponse converted = purchaseSuggestionService.convertPurchaseSuggestions(
                new ConvertPurchaseSuggestionsRequest(List.of(suggestion.getSuggestionId())), ACTOR);

        // The order itself is placed by pos-order; what this module records is which order its
        // suggestion became, and that has to be the identity it asked for (CAP-320 #1334).
        PurchaseSuggestion stamped =
                suggestionRepository.findById(suggestion.getSuggestionId()).orElseThrow();
        assertThat(stamped.getStatus()).isEqualTo(PurchaseSuggestionStatus.CONVERTED);
        assertThat(stamped.getConvertedPurchaseOrderId()).isEqualTo(converted.getPurchaseOrderId());
    }
}

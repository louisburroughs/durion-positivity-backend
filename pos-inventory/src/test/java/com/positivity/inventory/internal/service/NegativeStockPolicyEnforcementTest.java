package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.exception.InsufficientStockException;
import com.positivity.inventory.internal.exception.NegativeStockPolicyViolationException;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Per-event-type integration tests for the negative-stock policy matrix
 * enforced inside the ledger posting funnel (odoo-parity K1, issue #1027).
 *
 * <p>Matrix under test (see {@code docs/negative-stock-policy.md}):
 * <ul>
 *   <li>GOODS_ISSUE / WORKORDER_CONSUMPTION / TRANSFER_OUT — blocked below zero
 *       ({@link InsufficientStockException}, pre-K1 error contract);</li>
 *   <li>SCRAP_OUT — blocked below zero unless the caller passes the explicit
 *       negative-stock override flag;</li>
 *   <li>ADJUSTMENT_OUT / COUNT_VARIANCE_OUT / ADJUST_CYCLE_COUNT — floor at
 *       zero, never overridable;</li>
 *   <li>GOODS_RECEIPT / TRANSFER_IN / PUTAWAY / RETURN_TO_STOCK /
 *       ADJUSTMENT_IN / COUNT_VARIANCE_IN — unconstrained;</li>
 *   <li>neutral/ATP-only types — untouched by the matrix.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Negative-stock policy matrix enforcement in the ledger posting funnel (K1)")
class NegativeStockPolicyEnforcementTest {

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private InventoryLedgerEntryRepository ledgerRepository;

    @Autowired
    private InventoryStockSummaryRepository summaryRepository;

    private static String uniqueSku() {
        return "SKU-K1-" + UUID.randomUUID();
    }

    private InventoryLedgerEntry entry(String sku, UUID locationId, InventoryLedgerEventType type, BigDecimal change) {
        return InventoryLedgerEntry.builder()
                .stockItemId(sku)
                .locationId(locationId)
                .eventType(type)
                .changeInQuantity(change)
                .quantityAfter(new BigDecimal("0"))
                .transactionUserId("k1-policy-test")
                .build();
    }

    private void seed(String sku, UUID location, BigDecimal quantity) {
        ledgerPostingService.post(entry(sku, location, InventoryLedgerEventType.GOODS_RECEIPT, quantity));
    }

    private BigDecimal onHand(String sku, UUID location) {
        return summaryRepository
                .findByStockItemIdAndLocationId(sku, location)
                .orElseThrow()
                .getOnHand();
    }

    // ─── BLOCKED: GOODS_ISSUE / WORKORDER_CONSUMPTION / TRANSFER_OUT ─────────

    @Test
    void goodsIssue_belowZero_isBlockedWithInsufficientStock() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();
        seed(sku, location, new BigDecimal("5"));

        assertThatExceptionOfType(InsufficientStockException.class)
                .isThrownBy(() -> ledgerPostingService.post(
                        entry(sku, location, InventoryLedgerEventType.GOODS_ISSUE, new BigDecimal("-8"))));

        assertThat(onHand(sku, location)).isEqualByComparingTo("5");
        assertThat(ledgerRepository.findByStockItemIdOrderByTimestampAsc(sku)).hasSize(1);
    }

    @Test
    void workorderConsumption_belowZero_isBlockedWithInsufficientStock() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();
        seed(sku, location, new BigDecimal("2"));

        assertThatExceptionOfType(InsufficientStockException.class)
                .isThrownBy(() -> ledgerPostingService.post(
                        entry(sku, location, InventoryLedgerEventType.WORKORDER_CONSUMPTION, new BigDecimal("-3"))));

        assertThat(onHand(sku, location)).isEqualByComparingTo("2");
    }

    @Test
    void transferOut_belowZero_isBlockedWithInsufficientStock() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();
        seed(sku, location, new BigDecimal("1"));

        assertThatExceptionOfType(InsufficientStockException.class)
                .isThrownBy(() -> ledgerPostingService.post(
                        entry(sku, location, InventoryLedgerEventType.TRANSFER_OUT, new BigDecimal("-2"))));

        assertThat(onHand(sku, location)).isEqualByComparingTo("1");
    }

    @Test
    void goodsIssue_exactlyToZero_isAllowed() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();
        seed(sku, location, new BigDecimal("5"));

        ledgerPostingService.post(entry(sku, location, InventoryLedgerEventType.GOODS_ISSUE, new BigDecimal("-5")));

        assertThat(onHand(sku, location)).isZero();
    }

    // ─── BLOCKED_OVERRIDABLE: SCRAP_OUT ──────────────────────────────────────

    @Test
    void scrapOut_belowZero_withoutOverride_isRejectedWithOverrideRequiredCode() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();
        seed(sku, location, new BigDecimal("3"));

        assertThatExceptionOfType(NegativeStockPolicyViolationException.class)
                .isThrownBy(() -> ledgerPostingService.post(
                        entry(sku, location, InventoryLedgerEventType.SCRAP_OUT, new BigDecimal("-4"))))
                .satisfies(ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(NegativeStockPolicyViolationException.OVERRIDE_REQUIRED));

        assertThat(onHand(sku, location)).isEqualByComparingTo("3");
    }

    @Test
    void scrapOut_belowZero_withOverride_isAllowed() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();
        seed(sku, location, new BigDecimal("3"));

        // Caller has already verified inventory:adjustment:override and passes
        // the explicit flag — the funnel never reads the security context.
        ledgerPostingService.post(entry(sku, location, InventoryLedgerEventType.SCRAP_OUT, new BigDecimal("-5")), true);

        assertThat(onHand(sku, location)).isEqualByComparingTo("-2");
    }

    @Test
    void scrapOut_exactlyToZero_withoutOverride_isAllowed() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();
        seed(sku, location, new BigDecimal("3"));

        assertThatCode(() -> ledgerPostingService.post(
                        entry(sku, location, InventoryLedgerEventType.SCRAP_OUT, new BigDecimal("-3"))))
                .doesNotThrowAnyException();

        assertThat(onHand(sku, location)).isZero();
    }

    // ─── FLOOR_AT_ZERO: ADJUSTMENT_OUT / COUNT_VARIANCE_OUT ──────────────────

    @Test
    void adjustmentOut_belowZero_isRejectedEvenWithOverride() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();
        seed(sku, location, new BigDecimal("2"));

        assertThatExceptionOfType(NegativeStockPolicyViolationException.class)
                .isThrownBy(() -> ledgerPostingService.post(
                        entry(sku, location, InventoryLedgerEventType.ADJUSTMENT_OUT, new BigDecimal("-3")), true))
                .satisfies(ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(NegativeStockPolicyViolationException.FLOOR_VIOLATION));

        assertThat(onHand(sku, location)).isEqualByComparingTo("2");
    }

    @Test
    void adjustmentOut_exactlyToZero_isAllowed() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();
        seed(sku, location, new BigDecimal("2"));

        ledgerPostingService.post(entry(sku, location, InventoryLedgerEventType.ADJUSTMENT_OUT, new BigDecimal("-2")));

        assertThat(onHand(sku, location)).isZero();
    }

    @Test
    void countVarianceOut_belowZero_isRejected_andExactlyToZeroAllowed() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();
        seed(sku, location, new BigDecimal("4"));

        assertThatExceptionOfType(NegativeStockPolicyViolationException.class)
                .isThrownBy(() -> ledgerPostingService.post(
                        entry(sku, location, InventoryLedgerEventType.COUNT_VARIANCE_OUT, new BigDecimal("-5"))))
                .satisfies(ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(NegativeStockPolicyViolationException.FLOOR_VIOLATION));

        // Counts set reality: zeroing the balance is legitimate.
        ledgerPostingService.post(
                entry(sku, location, InventoryLedgerEventType.COUNT_VARIANCE_OUT, new BigDecimal("-4")));

        assertThat(onHand(sku, location)).isZero();
    }

    // ─── UNCONSTRAINED: inbound + paired-move types ──────────────────────────

    @Test
    void putawaySourceDecrement_belowZero_isUnconstrained() {
        String sku = uniqueSku();
        UUID stagingLocation = UUID.randomUUID();

        // Paired putaway moves decrement the source location with the same
        // PUTAWAY event type; the matrix leaves them unconstrained (the putaway
        // services validate source on-hand themselves).
        ledgerPostingService.post(entry(sku, stagingLocation, InventoryLedgerEventType.PUTAWAY, new BigDecimal("-4")));

        assertThat(onHand(sku, stagingLocation)).isEqualByComparingTo("-4");
    }

    @Test
    void inboundTypes_areUnconstrained() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();

        assertThatCode(() -> ledgerPostingService.postAll(List.of(
                        entry(sku, location, InventoryLedgerEventType.GOODS_RECEIPT, new BigDecimal("1")),
                        entry(sku, location, InventoryLedgerEventType.TRANSFER_IN, new BigDecimal("1")),
                        entry(sku, location, InventoryLedgerEventType.RETURN_TO_STOCK, new BigDecimal("1")),
                        entry(sku, location, InventoryLedgerEventType.ADJUSTMENT_IN, new BigDecimal("1")),
                        entry(sku, location, InventoryLedgerEventType.COUNT_VARIANCE_IN, new BigDecimal("1")))))
                .doesNotThrowAnyException();

        assertThat(onHand(sku, location)).isEqualByComparingTo("5");
    }

    // ─── Neutral/ATP-only types: untouched by the matrix ─────────────────────

    @Test
    void neutralAtpOnlyTypes_areUntouchedByTheMatrix() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();
        seed(sku, location, new BigDecimal("1"));

        // Reservations/allocations far beyond on-hand are an ATP concern
        // (InsufficientAtpException at the caller), never a negative-stock one.
        assertThatCode(() -> ledgerPostingService.postAll(List.of(
                        entry(sku, location, InventoryLedgerEventType.RESERVATION_CREATED, new BigDecimal("100")),
                        entry(sku, location, InventoryLedgerEventType.ALLOCATION_CREATED, new BigDecimal("100")),
                        entry(sku, location, InventoryLedgerEventType.BACKORDER_CREATED, new BigDecimal("100")),
                        entry(sku, location, InventoryLedgerEventType.PICK_TASK_CREATED, new BigDecimal("100")))))
                .doesNotThrowAnyException();

        assertThat(onHand(sku, location)).isEqualByComparingTo("1");
    }

    // ─── Batch semantics ─────────────────────────────────────────────────────

    @Test
    void batchViolation_rollsBackWholePosting() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();

        assertThatExceptionOfType(InsufficientStockException.class)
                .isThrownBy(() -> ledgerPostingService.postAll(List.of(
                        entry(sku, location, InventoryLedgerEventType.GOODS_RECEIPT, new BigDecimal("5")),
                        entry(sku, location, InventoryLedgerEventType.GOODS_ISSUE, new BigDecimal("-8")))));

        // Single enforcement point inside the posting transaction: the valid
        // receipt rolls back together with the rejected issue. (The summary row
        // itself is created in a REQUIRES_NEW nested transaction and may remain
        // as an empty zero-balance row.)
        assertThat(ledgerRepository.findByStockItemIdOrderByTimestampAsc(sku)).isEmpty();
        assertThat(summaryRepository
                        .findByStockItemIdAndLocationId(sku, location)
                        .map(row -> row.getOnHand())
                        .orElse(BigDecimal.ZERO))
                .isZero();
    }

    @Test
    void batchReplay_isSequential_receiptBeforeIssueInSameBatchIsAllowed() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();

        ledgerPostingService.postAll(List.of(
                entry(sku, location, InventoryLedgerEventType.GOODS_RECEIPT, new BigDecimal("5")),
                entry(sku, location, InventoryLedgerEventType.GOODS_ISSUE, new BigDecimal("-5"))));

        assertThat(onHand(sku, location)).isZero();
    }
}

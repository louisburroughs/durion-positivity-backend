package com.positivity.inventory.internal.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.inventory.GoodsReceiptLine;
import com.positivity.domainevents.inventory.GoodsReceiptRecordedV1;
import com.positivity.inventory.internal.config.OutboxEventWriter;
import com.positivity.inventory.internal.entity.GoodsReceiptEntity;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.CostingMethod;
import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Publishes {@code goodsreceipt.recorded} on {@code inventory.events.v1} (CAP-320 #1334).
 *
 * <h2>The seam between receiving and ordering</h2>
 *
 * This is how what physically arrived reaches the order that asked for it. pos-inventory used to
 * write the purchase order directly at this point, which made two modules writers of one
 * aggregate; now it states what it received and pos-order decides what that means — whether a line
 * is settled, whether the order is fully received, what is still owed.
 *
 * <h2>Published in the receiving transaction</h2>
 *
 * Through the outbox, so the fact and the ledger entries it accompanies commit together. A receipt
 * that posted stock but failed to tell the order would leave goods on the shelf and the order
 * still expecting them — the projection would go on promising supply that had already arrived.
 *
 * <h2>What inventory booked (CAP:550 S41 #2602, #2598)</h2>
 *
 * Each line also carries what the receipt put on inventory's books, read off the line's posted
 * {@code GOODS_RECEIPT} ledger row: its id, the costing method that priced it ({@code costSource})
 * and {@code inventoryValueMinor}, the received quantity at the unit cost the costing engine stamped
 * there, rounded HALF_UP to minor units of the order's currency. No cost is derived here; the row is
 * the source. The value is withheld (null) when the row is uncosted, and for an order outside the
 * functional currency, whose rows take no document cost (ADR-0067 DF-6). pos-accounting posts the
 * receipt's accrual from these (AW38).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoodsReceiptFactPublisher {

    private static final String SOURCE = "pos-inventory";

    /** {@code costSource} of a line whose ledger row the costing engine could not cost. */
    static final String UNCOSTED = "NONE";

    /** Optional so the publisher is inert where the outbox is not wired. */
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;

    private final Clock clock;

    private final CostingMethodResolver costingMethodResolver;

    private final ReceiptCostCurrencyPolicy receiptCostCurrencyPolicy;

    /**
     * Queues the receipt for publication, in the caller's transaction.
     *
     * @param currencyCode the purchase order's document currency, as its replica states it; never defaulted
     */
    public void publish(
            @NonNull GoodsReceiptEntity receipt,
            @Nullable String currencyCode,
            @NonNull List<GoodsReceiptLineFact> lines) {
        publish(receipt, currencyCode, lines, null);
    }

    /**
     * Queues the receipt for publication under a caller-chosen event id (#2455): a receiving
     * session derives it from the call's idempotency key, so pos-order's event-id de-duplication
     * also catches a retry that somehow reached the outbox twice. A null id takes a fresh one.
     */
    public void publish(
            @NonNull GoodsReceiptEntity receipt,
            @Nullable String currencyCode,
            @NonNull List<GoodsReceiptLineFact> lines,
            @Nullable UUID eventId) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }

        // One resolution for the receipt's SKUs, not one per line.
        Map<String, CostingMethod> methods = costingMethodResolver.resolveAll(lines.stream()
                .map(GoodsReceiptLineFact::receiptRow)
                .filter(row -> row != null && row.getUnitCost() != null)
                .map(InventoryLedgerEntry::getStockItemId)
                .collect(Collectors.toSet()));
        // An order outside the functional currency lends its rows no document cost (ADR-0067 DF-6), so
        // whatever the engine stamped is not a value in the fact's currency: none is stated.
        boolean valueInFactCurrency =
                receiptCostCurrencyPolicy.awaitingCostReason(currencyCode).isEmpty();
        List<GoodsReceiptLine> factLines = lines.stream()
                .map(line -> toFactLine(line, currencyCode, valueInFactCurrency, methods))
                .toList();
        GoodsReceiptRecordedV1 payload = new GoodsReceiptRecordedV1(
                receipt.getReceiptId(),
                receipt.getReceiptNumber(),
                receipt.getPurchaseOrderId(),
                receipt.getLocationId(),
                totalAccruedAmountMinor(factLines),
                Instant.now(clock),
                factLines,
                currencyCode);

        writer.publish(
                DomainTopics.events("inventory"),
                new DomainEventEnvelope<>(
                        eventId != null ? eventId : UUIDv7Generator.generate(),
                        GoodsReceiptRecordedV1.EVENT_TYPE,
                        GoodsReceiptRecordedV1.SCHEMA_VERSION,
                        // Keyed on the order rather than the receipt, so every receipt against one
                        // order lands on the same partition and they are applied in the sequence
                        // they happened. Two receipts applied out of order would still reach the
                        // right totals, but the order would pass through a state it was never in.
                        receipt.getPurchaseOrderId(),
                        0L,
                        Instant.now(clock),
                        SOURCE,
                        // tenantId: stamped by the outbox writer from the bound tenant (ADR-0062 §3)
                        null,
                        null,
                        receipt.getCreatedBy(),
                        payload));

        log.debug(
                "Queued goodsreceipt.recorded for receipt={} order={} lines={}",
                receipt.getReceiptId(),
                receipt.getPurchaseOrderId(),
                lines.size());
    }

    /**
     * The receipt's total accrual: the sum of its lines, each already whole minor units, so the total
     * and the lines always agree (the invariant pos-accounting checks before it posts).
     */
    static long totalAccruedAmountMinor(@NonNull List<GoodsReceiptLine> lines) {
        return lines.stream().mapToLong(GoodsReceiptLine::accruedAmountMinor).sum();
    }

    /** One fact line: the caller's quantity and accrual, and what the posted ledger row booked. */
    private static GoodsReceiptLine toFactLine(
            @NonNull GoodsReceiptLineFact line,
            @Nullable String currencyCode,
            boolean valueInFactCurrency,
            @NonNull Map<String, CostingMethod> methods) {
        InventoryLedgerEntry row = line.receiptRow();
        BigDecimal unitCost = row == null ? null : row.getUnitCost();
        String costSource =
                unitCost == null ? UNCOSTED : methods.get(row.getStockItemId()).name();
        Long inventoryValueMinor = unitCost == null || !valueInFactCurrency
                ? null
                : ReceiptUnitCosts.toMinorUnits(line.quantityReceived().multiply(unitCost), currencyCode);
        return new GoodsReceiptLine(
                line.poLineId(),
                line.sku(),
                line.quantityReceived(),
                line.accruedAmountMinor(),
                line.receiptLineId(),
                line.productId(),
                inventoryValueMinor,
                costSource,
                row == null ? null : row.getLedgerEntryId());
    }

    /**
     * What the caller has to state per line; deliberately narrower than the receipt entity.
     *
     * @param poLineId           the purchase-order line the line settles, when it is attributed
     * @param sku                stock reference as recorded on the receipt
     * @param quantityReceived   base quantity received
     * @param accruedAmountMinor the order's value of the line, whole minor units, rounded HALF_UP
     * @param receiptLineId      the saved goods-receipt line's id
     * @param productId          the product the ledger row posted against, when known
     * @param receiptRow         the line's posted {@code GOODS_RECEIPT} ledger row, cost stamped
     */
    public record GoodsReceiptLineFact(
            @Nullable UUID poLineId,
            @Nullable String sku,
            @NonNull BigDecimal quantityReceived,
            long accruedAmountMinor,
            @Nullable UUID receiptLineId,
            @Nullable UUID productId,
            @Nullable InventoryLedgerEntry receiptRow) {}
}

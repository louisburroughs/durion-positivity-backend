package com.positivity.inventory.internal.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.inventory.GoodsReceiptLine;
import com.positivity.domainevents.inventory.GoodsReceiptRecordedV1;
import com.positivity.inventory.internal.config.OutboxEventWriter;
import com.positivity.inventory.internal.entity.GoodsReceiptEntity;
import com.positivity.shared.id.UUIDv7Generator;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoodsReceiptFactPublisher {

    private static final String SOURCE = "pos-inventory";

    /** Optional so the publisher is inert where the outbox is not wired. */
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;

    private final Clock clock;

    /** Queues the receipt for publication, in the caller's transaction. */
    public void publish(@NonNull GoodsReceiptEntity receipt, @NonNull List<GoodsReceiptLineFact> lines) {
        publish(receipt, lines, null);
    }

    /**
     * Queues the receipt for publication under a caller-chosen event id (#2455): a receiving
     * session derives it from the call's idempotency key, so pos-order's event-id de-duplication
     * also catches a retry that somehow reached the outbox twice. A null id takes a fresh one.
     */
    public void publish(
            @NonNull GoodsReceiptEntity receipt,
            @NonNull List<GoodsReceiptLineFact> lines,
            java.util.@Nullable UUID eventId) {
        publish(
                new ReceiptHeader(
                        receipt.getReceiptId(),
                        receipt.getReceiptNumber(),
                        receipt.getPurchaseOrderId(),
                        receipt.getLocationId(),
                        receipt.getCreatedBy()),
                lines,
                eventId);
    }

    /** Queues a receipt that has no goods-receipt document of its own for publication. */
    public void publish(@NonNull ReceiptHeader receipt, @NonNull List<GoodsReceiptLineFact> lines) {
        publish(receipt, lines, null);
    }

    private void publish(
            @NonNull ReceiptHeader receipt,
            @NonNull List<GoodsReceiptLineFact> lines,
            java.util.@Nullable UUID eventId) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }

        long total = lines.stream()
                .mapToLong(GoodsReceiptLineFact::accruedAmountMinor)
                .sum();
        GoodsReceiptRecordedV1 payload = new GoodsReceiptRecordedV1(
                receipt.receiptId(),
                receipt.receiptNumber(),
                receipt.purchaseOrderId(),
                receipt.locationId(),
                total,
                Instant.now(clock),
                lines.stream()
                        .map(line -> new GoodsReceiptLine(
                                line.poLineId(), line.sku(), line.quantityReceived(), line.accruedAmountMinor()))
                        .toList());

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
                        receipt.purchaseOrderId(),
                        0L,
                        Instant.now(clock),
                        SOURCE,
                        // tenantId: stamped by the outbox writer from the bound tenant (ADR-0062 §3)
                        null,
                        null,
                        receipt.recordedBy(),
                        payload));

        log.debug(
                "Queued goodsreceipt.recorded for receipt={} order={} lines={}",
                receipt.receiptId(),
                receipt.purchaseOrderId(),
                lines.size());
    }

    /**
     * The receipt-level facts {@code goodsreceipt.recorded} carries.
     *
     * @param receiptId       identity of this receipt; fresh per receive when there is no document
     * @param receiptNumber   human-readable number, when the receipt has one
     * @param purchaseOrderId the order received against
     * @param locationId      where the goods were received
     * @param recordedBy      who recorded it
     */
    public record ReceiptHeader(
            java.util.@NonNull UUID receiptId,
            @Nullable String receiptNumber,
            java.util.@NonNull UUID purchaseOrderId,
            java.util.@Nullable UUID locationId,
            @Nullable String recordedBy) {}

    /** What the caller has to state per line; deliberately narrower than the receipt entity. */
    public record GoodsReceiptLineFact(
            java.util.UUID poLineId, String sku, java.math.BigDecimal quantityReceived, long accruedAmountMinor) {}
}

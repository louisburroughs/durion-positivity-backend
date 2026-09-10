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
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }

        long total = lines.stream()
                .mapToLong(GoodsReceiptLineFact::accruedAmountMinor)
                .sum();
        GoodsReceiptRecordedV1 payload = new GoodsReceiptRecordedV1(
                receipt.getReceiptId(),
                receipt.getReceiptNumber(),
                receipt.getPurchaseOrderId(),
                receipt.getLocationId(),
                total,
                Instant.now(clock),
                lines.stream()
                        .map(line -> new GoodsReceiptLine(
                                line.poLineId(), line.sku(), line.quantityReceived(), line.accruedAmountMinor()))
                        .toList());

        writer.publish(
                DomainTopics.events("inventory"),
                new DomainEventEnvelope<>(
                        UUIDv7Generator.generate(),
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
                        null,
                        receipt.getCreatedBy(),
                        payload));

        log.debug(
                "Queued goodsreceipt.recorded for receipt={} order={} lines={}",
                receipt.getReceiptId(),
                receipt.getPurchaseOrderId(),
                lines.size());
    }

    /** What the caller has to state per line; deliberately narrower than the receipt entity. */
    public record GoodsReceiptLineFact(
            java.util.UUID poLineId, String sku, java.math.BigDecimal quantityReceived, long accruedAmountMinor) {}
}

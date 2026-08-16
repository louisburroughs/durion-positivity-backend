package com.positivity.inventory.internal.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.order.PurchaseOrderRequestedV1;
import com.positivity.inventory.internal.config.OutboxEventWriter;
import com.positivity.shared.id.UUIDv7Generator;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Asks pos-order to place a purchase order, on {@code order.commands.v1} (CAP-320 #1334).
 *
 * <h2>Asking rather than calling</h2>
 *
 * Replenishment decides that stock needs ordering, and ordering belongs to pos-order. This used to
 * be a direct call to a purchase-order service inside pos-inventory — the cross-domain write
 * ADR-0044 R1 removes. Raising a command keeps the decision here and the order there.
 *
 * <p>Published through the outbox, in the transaction that marks the suggestions converted, so a
 * suggestion cannot end up marked converted with no order ever requested.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PurchaseOrderCommandPublisher {

    private static final String SOURCE = "pos-inventory";

    /** Optional so the publisher is inert where the outbox is not wired. */
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;

    private final Clock clock;

    /** Queues the request for publication, in the caller's transaction. */
    public void request(@NonNull PurchaseOrderRequestedV1 command) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }

        writer.publish(
                DomainTopics.commands("order"),
                new DomainEventEnvelope<>(
                        UUIDv7Generator.generate(),
                        PurchaseOrderRequestedV1.EVENT_TYPE,
                        PurchaseOrderRequestedV1.SCHEMA_VERSION,
                        command.purchaseOrderId(),
                        0L,
                        Instant.now(clock),
                        SOURCE,
                        null,
                        command.requestedBy(),
                        command));

        log.info(
                "Requested purchase order {} from pos-order: vendor={} lines={}",
                command.purchaseOrderId(),
                command.vendorId(),
                command.lines().size());
    }
}

package com.positivity.supplier.internal.order.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.supplier.SupplierOrderNotDispatchedV1;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.supplier.internal.service.SupplierOutboxEventWriter;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers an order command pos-supplier cannot dispatch because the vendor is not set up for
 * electronic ordering, with {@code supplier.order.notdispatched} (ADR-0049 §3, #2492).
 *
 * <p>{@code MANDATORY} propagation: the outbox row commits in the same transaction as the
 * {@code processed_events} mark of the command it answers, so the command is never recorded as
 * consumed without the ordering domain being told, and never answered twice.
 *
 * <p>No transmission intent exists for a never-dispatched order, so the envelope is keyed by the
 * purchase order id, with the requested revision as its aggregate version.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderNotDispatchedPublisher {

    private static final String SOURCE = "pos-supplier";

    private final SupplierOutboxEventWriter outboxWriter;
    private final Clock clock;

    /**
     * Queues the not-dispatched answer.
     *
     * @param purchaseOrderId the purchase order that was requested
     * @param requestedRevision revision the command asked to transmit
     * @param supplierRef alias the command named
     * @param vendorProfileId the disabled profile's id, or null when none exists
     * @param detail what was wrong with the vendor configuration
     * @param commandEventId event id of the command being answered
     * @param correlationId correlation of the command
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(
            @NonNull UUID purchaseOrderId,
            int requestedRevision,
            @NonNull String supplierRef,
            @Nullable UUID vendorProfileId,
            @NonNull String detail,
            @NonNull UUID commandEventId,
            @Nullable String correlationId) {
        Instant now = Instant.now(clock);
        SupplierOrderNotDispatchedV1 payload = new SupplierOrderNotDispatchedV1(
                purchaseOrderId,
                supplierRef,
                vendorProfileId,
                SupplierOrderNotDispatchedV1.Reason.SUPPLIER_NOT_CONFIGURED,
                detail,
                requestedRevision,
                commandEventId,
                now);
        outboxWriter.publish(
                DomainTopics.events("supplier"),
                new DomainEventEnvelope<>(
                        UUIDv7Generator.generate(),
                        SupplierOrderNotDispatchedV1.EVENT_TYPE,
                        SupplierOrderNotDispatchedV1.SCHEMA_VERSION,
                        purchaseOrderId,
                        requestedRevision,
                        now,
                        SOURCE,
                        // tenantId: stamped by the outbox writer from the bound tenant (ADR-0062 §3)
                        null,
                        correlationId,
                        null,
                        payload));
        log.warn(
                "Order command {} for purchase order {} revision {} not dispatched: {}",
                commandEventId,
                purchaseOrderId,
                requestedRevision,
                detail);
    }
}

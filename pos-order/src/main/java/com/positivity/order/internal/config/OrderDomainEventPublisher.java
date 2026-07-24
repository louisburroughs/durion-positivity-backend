package com.positivity.order.internal.config;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.order.OrderCancelReviewRequiredV1;
import com.positivity.domainevents.order.OrderCancelledV1;
import com.positivity.domainevents.order.OrderCommissionImpactV1;
import com.positivity.domainevents.order.OrderCompletedV1;
import com.positivity.domainevents.order.OrderPaymentIntegrityAlertV1;
import com.positivity.domainevents.order.OrderReturnedV1;
import com.positivity.domainevents.order.RegisterSessionClosedV1;
import com.positivity.order.internal.entity.PriceOverride;
import com.positivity.order.internal.entity.RegisterSession;
import com.positivity.order.internal.entity.ReturnOrder;
import com.positivity.order.internal.entity.SalesOrder;
import com.positivity.order.internal.entity.SalesOrderLine;
import com.positivity.order.internal.entity.SourceType;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Emits order domain facts to the outbox (ADR-0044, plan stories D1/D2/D3). No-op while the Kafka
 * feature flag ({@code pos.order.kafka.enabled}) is off — the {@link OutboxEventWriter} bean is
 * conditional, so callers degrade gracefully. Must be called inside the mutating transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderDomainEventPublisher {

    private static final String SOURCE_SERVICE = "pos-order";
    private static final String CURRENCY = "USD";

    private final Clock clock;
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;

    public void publishOrderCancelled(@NonNull SalesOrder order) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        OrderCancelledV1 payload = new OrderCancelledV1(
                order.getOrderId(),
                order.getOrderNumber(),
                order.getWorkOrderId(),
                order.getCancellationReason(),
                Instant.now(clock));
        publish(writer, OrderCancelledV1.EVENT_TYPE, OrderCancelledV1.SCHEMA_VERSION, order, payload);
        log.debug("Queued order.order.cancelled orderId={}", order.getOrderId());
    }

    public void publishCancelReviewRequired(@NonNull SalesOrder order, @NonNull String failureReason) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        OrderCancelReviewRequiredV1 payload = new OrderCancelReviewRequiredV1(
                order.getOrderId(), order.getOrderNumber(), order.getWorkOrderId(), failureReason, Instant.now(clock));
        publish(
                writer,
                OrderCancelReviewRequiredV1.EVENT_TYPE,
                OrderCancelReviewRequiredV1.SCHEMA_VERSION,
                order,
                payload);
        log.debug("Queued order.order.cancel-review-required orderId={}", order.getOrderId());
    }

    /**
     * Emits {@code order.order.completed} (story D2, resolved Q9: rich but PII-minimal).
     * {@code tenders} is the net settlement summary from the order's payment records.
     */
    public void publishOrderCompleted(@NonNull SalesOrder order, @NonNull List<OrderCompletedV1.Tender> tenders) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        List<OrderCompletedV1.Line> lines = order.getLines().stream()
                .filter(Objects::nonNull)
                .map(OrderDomainEventPublisher::toEventLine)
                .toList();
        OrderCompletedV1 payload = new OrderCompletedV1(
                order.getOrderId(),
                order.getOrderNumber(),
                order.getLocationId(),
                order.getWorkOrderId(),
                order.getSessionId(),
                order.getCustomerId(),
                order.getVehicleId(),
                order.getClerkId(),
                order.getTerminalId(),
                CURRENCY,
                order.getSubtotal(),
                order.getDiscountTotal(),
                order.getTaxTotal(),
                order.getGrandTotal(),
                lines,
                tenders,
                Instant.now(clock));
        publish(writer, OrderCompletedV1.EVENT_TYPE, OrderCompletedV1.SCHEMA_VERSION, order, payload);
        log.debug("Queued order.order.completed orderId={}", order.getOrderId());
    }

    /** Emits {@code order.payment.integrity-alert} on over-settlement (story C3, spec R4.4). */
    public void publishPaymentIntegrityAlert(@NonNull SalesOrder order) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        OrderPaymentIntegrityAlertV1 payload = new OrderPaymentIntegrityAlertV1(
                order.getOrderId(),
                order.getOrderNumber(),
                order.getInvoiceId(),
                order.getGrandTotal(),
                order.getAmountPaid(),
                Instant.now(clock));
        publish(
                writer,
                OrderPaymentIntegrityAlertV1.EVENT_TYPE,
                OrderPaymentIntegrityAlertV1.SCHEMA_VERSION,
                order,
                payload);
        log.warn(
                "Queued order.payment.integrity-alert orderId={} amountPaid={} grandTotal={}",
                order.getOrderId(),
                order.getAmountPaid(),
                order.getGrandTotal());
    }

    /** Emits {@code order.line.commission-impact} for an applied override (story D3, resolved Q7). */
    public void publishCommissionImpact(@NonNull PriceOverride override) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        SalesOrder order = override.getOrder();
        OrderCommissionImpactV1 payload = new OrderCommissionImpactV1(
                order.getOrderId(),
                override.getOrderLine().getOrderLineId(),
                override.getOverrideId(),
                override.getProductId(),
                Boolean.TRUE.equals(override.getAffectsCommission()),
                override.getOriginalPrice(),
                override.getOverridePrice(),
                override.getOriginalPrice().subtract(override.getOverridePrice()),
                override.getRequestedByUserId(),
                override.getApprovedByUserId(),
                override.getReasonCode() == null
                        ? "UNSPECIFIED"
                        : override.getReasonCode().name(),
                override.getApprovedAt() != null ? override.getApprovedAt() : Instant.now(clock));
        publish(writer, OrderCommissionImpactV1.EVENT_TYPE, OrderCommissionImpactV1.SCHEMA_VERSION, order, payload);
        log.debug("Queued order.line.commission-impact overrideId={}", override.getOverrideId());
    }

    /**
     * Emits {@code order.order.returned} (story F2, spec R5.3) with the return as the aggregate.
     * pos-inventory restocks RESTOCK-condition lines (SCRAP skipped); the fact is the durable record
     * of the completed return.
     */
    public void publishOrderReturned(@NonNull ReturnOrder returnOrder) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        List<OrderReturnedV1.Line> lines = returnOrder.getLines().stream()
                .filter(Objects::nonNull)
                .map(l -> new OrderReturnedV1.Line(
                        l.getOriginalOrderLineId(),
                        l.getItemSku(),
                        l.getReturnQty(),
                        l.getCondition().name(),
                        l.getLineRefund(),
                        l.getSerialNumbers() == null ? List.of() : List.copyOf(l.getSerialNumbers())))
                .toList();
        OrderReturnedV1 payload = new OrderReturnedV1(
                returnOrder.getReturnOrderId(),
                returnOrder.getOriginalOrderId(),
                returnOrder.getOriginalOrderNumber(),
                returnOrder.getLocationId(),
                returnOrder.getCustomerId(),
                returnOrder.getRefundMethod().name(),
                returnOrder.getTotalRefund(),
                lines,
                // Carry the persisted completion timestamp so the fact matches the aggregate state.
                returnOrder.getReturnedAt() != null ? returnOrder.getReturnedAt() : Instant.now(clock));
        long aggregateVersion = returnOrder.getVersion() == null ? 0L : returnOrder.getVersion();
        writer.publish(
                DomainTopics.events("order"),
                DomainEventEnvelope.of(
                        OrderReturnedV1.EVENT_TYPE,
                        OrderReturnedV1.SCHEMA_VERSION,
                        returnOrder.getReturnOrderId(),
                        aggregateVersion,
                        SOURCE_SERVICE,
                        null,
                        SecurityContextHelper.getCurrentUsernameOrDefault("system"),
                        payload,
                        clock));
        log.debug("Queued order.order.returned returnOrderId={}", returnOrder.getReturnOrderId());
    }

    /**
     * Emits {@code order.session.closed} (story G2, spec R6.4) with the session as the aggregate.
     * pos-accounting posts the over/short variance to GL (story G3).
     */
    public void publishRegisterSessionClosed(
            @NonNull RegisterSession session, @NonNull RegisterSessionClosedV1 payload) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        long aggregateVersion = session.getVersion() == null ? 0L : session.getVersion();
        writer.publish(
                DomainTopics.events("order"),
                DomainEventEnvelope.of(
                        RegisterSessionClosedV1.EVENT_TYPE,
                        RegisterSessionClosedV1.SCHEMA_VERSION,
                        session.getSessionId(),
                        aggregateVersion,
                        SOURCE_SERVICE,
                        null,
                        SecurityContextHelper.getCurrentUsernameOrDefault("system"),
                        payload,
                        clock));
        log.debug("Queued order.session.closed sessionId={}", session.getSessionId());
    }

    private void publish(
            OutboxEventWriter writer, String eventType, int schemaVersion, SalesOrder order, Object payload) {
        writer.publish(
                DomainTopics.events("order"),
                DomainEventEnvelope.of(
                        eventType,
                        schemaVersion,
                        order.getOrderId(),
                        aggregateVersion(order),
                        SOURCE_SERVICE,
                        null,
                        SecurityContextHelper.getCurrentUsernameOrDefault("system"),
                        payload,
                        clock));
    }

    private static OrderCompletedV1.Line toEventLine(SalesOrderLine line) {
        boolean workorderSourced = SourceType.WORKORDER.equals(line.getSourceType());
        return new OrderCompletedV1.Line(
                line.getOrderLineId(),
                line.getItemSku(),
                line.getItemDescription(),
                line.getQuantity(),
                line.getUnitPrice(),
                line.getDiscountAmount(),
                line.getLineSubtotal(),
                line.getTaxAmount(),
                line.getLineTotal(),
                line.getPriceSource() == null
                        ? "PRICING_SERVICE"
                        : line.getPriceSource().name(),
                line.getSourceType() == null ? null : line.getSourceType().name(),
                line.getSourceId(),
                line.getSourceLineId(),
                line.getReturnable(),
                line.getSerialNumbers() == null ? List.of() : List.copyOf(line.getSerialNumbers()),
                // Spec R7.5: WORKORDER-sourced lines are fulfilled by the source document —
                // pos-inventory must not double-consume them.
                !workorderSourced);
    }

    private static long aggregateVersion(SalesOrder order) {
        return order.getVersion() == null ? 0L : order.getVersion();
    }
}

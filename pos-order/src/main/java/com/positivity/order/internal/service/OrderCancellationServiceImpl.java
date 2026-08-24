package com.positivity.order.internal.service;

import com.positivity.order.internal.client.CancelWorkorderCommand;
import com.positivity.order.internal.client.InvoicingPort;
import com.positivity.order.internal.client.PaymentReversalResult;
import com.positivity.order.internal.client.ReversePaymentCommand;
import com.positivity.order.internal.client.WorkexecPort;
import com.positivity.order.internal.client.WorkorderCancelResult;
import com.positivity.order.internal.client.WorkorderStatusResult;
import com.positivity.order.internal.config.OrderDomainEventPublisher;
import com.positivity.order.internal.entity.OrderPaymentRecord;
import com.positivity.order.internal.entity.SalesOrder;
import com.positivity.order.internal.entity.SalesOrderStatus;
import com.positivity.order.internal.exception.SalesOrderNotFoundException;
import com.positivity.order.internal.repository.OrderPaymentRecordRepository;
import com.positivity.order.internal.repository.SalesOrderRepository;
import com.positivity.order.service.OrderCancellationService;
import com.positivity.order.service.model.CancelOrderCommand;
import com.positivity.order.service.model.CancellationResult;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderCancellationServiceImpl implements OrderCancellationService {
    private static final String SYSTEM_ACTOR = "system";

    private static final String STATUS_CANCELLED = "CANCELLED";

    private final SalesOrderRepository salesOrderRepository;
    private final OrderPaymentRecordRepository paymentRecordRepository;
    private final WorkexecPort workexecPort;
    private final InvoicingPort invoicingPort;
    private final OrderStateMachine orderStateMachine;
    private final OrderDomainEventPublisher domainEventPublisher;

    @Override
    @Transactional
    public @NonNull CancellationResult cancelOrder(@NonNull UUID orderId, @NonNull CancelOrderCommand command) {
        SalesOrder order =
                salesOrderRepository.findById(orderId).orElseThrow(() -> new SalesOrderNotFoundException(orderId));

        if (order.getStatus() == SalesOrderStatus.CANCELLED
                && command.idempotencyKey() != null
                && command.idempotencyKey().equals(order.getCancellationIdempotencyKey())) {
            return new CancellationResult(
                    order.getOrderId(),
                    STATUS_CANCELLED,
                    "Order already cancelled",
                    order.getCancellationIdempotencyKey());
        }

        Set<SalesOrderStatus> cancellable = Set.of(
                SalesOrderStatus.DRAFT,
                SalesOrderStatus.QUOTED,
                SalesOrderStatus.CANCEL_FAILED_WORKEXEC,
                SalesOrderStatus.CANCEL_FAILED_BILLING);

        if (!cancellable.contains(order.getStatus())) {
            throw new IllegalStateException("Order cannot be cancelled in current state: " + order.getStatus());
        }

        String idempotencyKey = command.idempotencyKey() != null
                ? command.idempotencyKey()
                : UUIDv7Generator.generate().toString();
        String actor = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM_ACTOR);

        orderStateMachine.transition(order, SalesOrderStatus.CANCEL_REQUESTED, command.cancellationReason());
        order.setCancellationReason(command.cancellationReason());
        order.setCancellationIdempotencyKey(idempotencyKey);
        if (command.workOrderId() != null) {
            order.setWorkOrderId(command.workOrderId());
        }
        order.setUpdatedBy(actor);
        salesOrderRepository.save(order);

        if (command.workOrderId() != null) {
            WorkorderStatusResult statusResult = workexecPort.checkWorkorderStatus(command.workOrderId());
            if (!statusResult.cancellable()) {
                failTransition(order, SalesOrderStatus.CANCEL_FAILED_WORKEXEC, statusResult.nonCancellableReason());
                throw new IllegalStateException(
                        "Workorder cannot be cancelled: " + statusResult.nonCancellableReason());
            }

            WorkorderCancelResult cancelResult = workexecPort.cancelWorkorder(
                    command.workOrderId(),
                    new CancelWorkorderCommand(orderId, actor, command.cancellationReason(), idempotencyKey));

            if (!cancelResult.success()) {
                failTransition(order, SalesOrderStatus.CANCEL_FAILED_WORKEXEC, cancelResult.resultMessage());
                throw new IllegalStateException("Workorder cancellation failed: " + cancelResult.resultMessage());
            }

            orderStateMachine.transition(order, SalesOrderStatus.WORKORDER_CANCELLED, null);
            order.setUpdatedBy(actor);
            salesOrderRepository.save(order);
        }

        reverseSettledPayments(order, command.cancellationReason(), idempotencyKey, actor);

        completeCancellation(order, actor);

        return new CancellationResult(orderId, STATUS_CANCELLED, "Order cancelled successfully", idempotencyKey);
    }

    @Override
    @Transactional
    public @NonNull CancellationResult retryCancellation(@NonNull UUID orderId, @NonNull String idempotencyKey) {
        SalesOrder order =
                salesOrderRepository.findById(orderId).orElseThrow(() -> new SalesOrderNotFoundException(orderId));
        String actor = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM_ACTOR);

        if (order.getStatus() != SalesOrderStatus.CANCEL_FAILED_BILLING) {
            throw new IllegalStateException(
                    "Order retry cancellation only allowed from CANCEL_FAILED_BILLING state: " + order.getStatus());
        }

        try {
            reverseSettledPayments(order, order.getCancellationReason(), idempotencyKey, actor);
        } catch (IllegalStateException e) {
            // A failed retry is terminal for automation (plan story A4): park the order for a
            // human instead of looping on CANCEL_FAILED_BILLING, and raise the alert fact.
            String failureReason = "Payment reversal retry failed: " + e.getMessage();
            orderStateMachine.transition(order, SalesOrderStatus.CANCEL_REQUIRES_MANUAL_REVIEW, failureReason);
            order.setUpdatedBy(actor);
            salesOrderRepository.save(order);
            domainEventPublisher.publishCancelReviewRequired(order, failureReason);
            log.error("Order {} cancellation requires manual review: {}", orderId, e.getMessage());
            throw new IllegalStateException(failureReason);
        }

        completeCancellation(order, actor);

        return new CancellationResult(
                orderId, STATUS_CANCELLED, "Order cancellation completed on retry", idempotencyKey);
    }

    /**
     * Spec R4.6: reverse every net-settled payment on the order's ledger (story C3 read model),
     * not a single caller-supplied payment id. Refunds are idempotent at pos-invoice via the
     * per-intent externalReference, so a saga retry never double-refunds. No settled payments —
     * the normal case for DRAFT/QUOTED cancels — is a no-op.
     */
    private void reverseSettledPayments(SalesOrder order, String reason, String idempotencyKey, String actor) {
        Map<UUID, BigDecimal> netByIntent = netSettledByIntent(order.getOrderId());
        if (netByIntent.isEmpty()) {
            return;
        }
        if (order.getInvoiceId() == null) {
            markBillingFailed(order, "Settled payments but no invoice reference");
            throw new IllegalStateException("Order has settled payments but no invoice reference to reverse against");
        }

        // The transition is recorded on the CANCEL_REQUESTED path only; retries re-enter from
        // CANCEL_FAILED_BILLING and go straight back through the reversal calls.
        for (Map.Entry<UUID, BigDecimal> entry : netByIntent.entrySet()) {
            PaymentReversalResult reversalResult = invoicingPort.reversePayment(
                    order.getInvoiceId(),
                    entry.getKey(),
                    new ReversePaymentCommand(
                            "REFUND",
                            entry.getValue(),
                            "USD",
                            reason,
                            order.getOrderId(),
                            idempotencyKey + "-" + entry.getKey()));
            if (!reversalResult.success()) {
                markBillingFailed(order, reversalResult.resultMessage());
                throw new IllegalStateException("Payment reversal failed: " + reversalResult.resultMessage());
            }
        }

        orderStateMachine.transition(order, SalesOrderStatus.PAYMENT_REVERSED, null);
        order.setUpdatedBy(actor);
        salesOrderRepository.save(order);
    }

    /** Net settled amount per payment intent: Σ SETTLED − Σ REVERSED, positive entries only. */
    private Map<UUID, BigDecimal> netSettledByIntent(UUID orderId) {
        List<OrderPaymentRecord> records = paymentRecordRepository.findByOrderId(orderId);
        Map<UUID, BigDecimal> net = new LinkedHashMap<>();
        for (OrderPaymentRecord record : records) {
            if (record.getPaymentIntentId() == null) {
                continue;
            }
            BigDecimal signed = record.getRecordType() == OrderPaymentRecord.RecordType.SETTLED
                    ? record.getAmount()
                    : record.getAmount().negate();
            net.merge(record.getPaymentIntentId(), signed, BigDecimal::add);
        }
        net.values().removeIf(amount -> amount.signum() <= 0);
        return net;
    }

    private void failTransition(SalesOrder order, SalesOrderStatus failureStatus, String reason) {
        orderStateMachine.transition(order, failureStatus, reason);
        order.setUpdatedBy(SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM_ACTOR));
        salesOrderRepository.save(order);
    }

    /** A retry re-enters from CANCEL_FAILED_BILLING; a same-state "transition" would be rejected. */
    private void markBillingFailed(SalesOrder order, String reason) {
        if (order.getStatus() != SalesOrderStatus.CANCEL_FAILED_BILLING) {
            failTransition(order, SalesOrderStatus.CANCEL_FAILED_BILLING, reason);
        }
    }

    private void completeCancellation(SalesOrder order, String actor) {
        orderStateMachine.transition(order, SalesOrderStatus.CANCELLED, null);
        order.setUpdatedBy(actor);
        salesOrderRepository.save(order);
        domainEventPublisher.publishOrderCancelled(order);
    }
}

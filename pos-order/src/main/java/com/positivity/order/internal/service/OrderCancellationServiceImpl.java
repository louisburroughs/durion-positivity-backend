package com.positivity.order.internal.service;

import com.positivity.order.internal.client.BillingPort;
import com.positivity.order.internal.client.CancelWorkorderCommand;
import com.positivity.order.internal.client.PaymentReversalResult;
import com.positivity.order.internal.client.ReversePaymentCommand;
import com.positivity.order.internal.client.WorkexecPort;
import com.positivity.order.internal.client.WorkorderCancelResult;
import com.positivity.order.internal.client.WorkorderStatusResult;
import com.positivity.order.internal.config.OrderDomainEventPublisher;
import com.positivity.order.internal.entity.SalesOrder;
import com.positivity.order.internal.entity.SalesOrderStatus;
import com.positivity.order.internal.exception.SalesOrderNotFoundException;
import com.positivity.order.internal.repository.SalesOrderRepository;
import com.positivity.order.service.OrderCancellationService;
import com.positivity.order.service.model.CancelOrderCommand;
import com.positivity.order.service.model.CancellationResult;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Generator;
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

    private static final String STATUS_CANCELLED = "CANCELLED";

    private final SalesOrderRepository salesOrderRepository;
    private final WorkexecPort workexecPort;
    private final BillingPort billingPort;
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
        String actor = SecurityContextHelper.getCurrentUsernameOrDefault("system");

        orderStateMachine.transition(order, SalesOrderStatus.CANCEL_REQUESTED, command.cancellationReason());
        order.setCancellationReason(command.cancellationReason());
        order.setCancellationIdempotencyKey(idempotencyKey);
        if (command.workOrderId() != null) {
            order.setWorkOrderId(command.workOrderId());
        }
        if (command.paymentId() != null) {
            order.setPaymentId(command.paymentId());
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

        if (command.paymentId() != null) {
            PaymentReversalResult reversalResult = billingPort.reversePayment(
                    command.paymentId(),
                    new ReversePaymentCommand("VOID", null, command.cancellationReason(), orderId, idempotencyKey));

            if (!reversalResult.success()) {
                failTransition(order, SalesOrderStatus.CANCEL_FAILED_BILLING, reversalResult.resultMessage());
                throw new IllegalStateException("Payment reversal failed: " + reversalResult.resultMessage());
            }

            orderStateMachine.transition(order, SalesOrderStatus.PAYMENT_REVERSED, null);
            order.setUpdatedBy(actor);
            salesOrderRepository.save(order);
        }

        completeCancellation(order, actor);

        return new CancellationResult(orderId, STATUS_CANCELLED, "Order cancelled successfully", idempotencyKey);
    }

    @Override
    @Transactional
    public @NonNull CancellationResult retryCancellation(@NonNull UUID orderId, @NonNull String idempotencyKey) {
        SalesOrder order =
                salesOrderRepository.findById(orderId).orElseThrow(() -> new SalesOrderNotFoundException(orderId));
        String actor = SecurityContextHelper.getCurrentUsernameOrDefault("system");

        if (order.getStatus() != SalesOrderStatus.CANCEL_FAILED_BILLING) {
            throw new IllegalStateException(
                    "Order retry cancellation only allowed from CANCEL_FAILED_BILLING state: " + order.getStatus());
        }

        PaymentReversalResult reversalResult = billingPort.reversePayment(
                order.getPaymentId(), new ReversePaymentCommand("VOID", null, null, orderId, idempotencyKey));

        if (!reversalResult.success()) {
            // A failed retry is terminal for automation (plan story A4): park the order for a
            // human instead of looping on CANCEL_FAILED_BILLING, and raise the alert fact.
            String failureReason = "Payment reversal retry failed: " + reversalResult.resultMessage();
            orderStateMachine.transition(order, SalesOrderStatus.CANCEL_REQUIRES_MANUAL_REVIEW, failureReason);
            order.setUpdatedBy(actor);
            salesOrderRepository.save(order);
            domainEventPublisher.publishCancelReviewRequired(order, failureReason);
            log.error("Order {} cancellation requires manual review: {}", orderId, reversalResult.resultMessage());
            throw new IllegalStateException(failureReason);
        }

        orderStateMachine.transition(order, SalesOrderStatus.PAYMENT_REVERSED, null);
        order.setUpdatedBy(actor);
        salesOrderRepository.save(order);

        completeCancellation(order, actor);

        return new CancellationResult(
                orderId, STATUS_CANCELLED, "Order cancellation completed on retry", idempotencyKey);
    }

    private void failTransition(SalesOrder order, SalesOrderStatus failureStatus, String reason) {
        orderStateMachine.transition(order, failureStatus, reason);
        order.setUpdatedBy(SecurityContextHelper.getCurrentUsernameOrDefault("system"));
        salesOrderRepository.save(order);
    }

    private void completeCancellation(SalesOrder order, String actor) {
        orderStateMachine.transition(order, SalesOrderStatus.CANCELLED, null);
        order.setUpdatedBy(actor);
        salesOrderRepository.save(order);
        domainEventPublisher.publishOrderCancelled(order);
    }
}

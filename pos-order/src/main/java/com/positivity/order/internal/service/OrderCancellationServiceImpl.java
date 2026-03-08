package com.positivity.order.internal.service;

import com.positivity.order.internal.client.BillingPort;
import com.positivity.order.internal.client.CancelWorkorderCommand;
import com.positivity.order.internal.client.PaymentReversalResult;
import com.positivity.order.internal.client.ReversePaymentCommand;
import com.positivity.order.internal.client.WorkexecPort;
import com.positivity.order.internal.client.WorkorderCancelResult;
import com.positivity.order.internal.client.WorkorderStatusResult;
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

    @Override
    @Transactional
    public @NonNull CancellationResult cancelOrder(@NonNull UUID orderId, @NonNull CancelOrderCommand command) {
        SalesOrder order = salesOrderRepository.findById(orderId)
                .orElseThrow(() -> new SalesOrderNotFoundException(orderId));

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

        order.setStatus(SalesOrderStatus.CANCEL_REQUESTED);
        order.setCancellationReason(command.cancellationReason());
        order.setCancellationIdempotencyKey(idempotencyKey);
        order.setWorkOrderId(command.workOrderId());
        order.setPaymentId(command.paymentId());
        order.setUpdatedBy(actor);
        salesOrderRepository.save(order);

        if (command.workOrderId() != null) {
            WorkorderStatusResult statusResult = workexecPort.checkWorkorderStatus(command.workOrderId());
            if (!statusResult.cancellable()) {
                order.setStatus(SalesOrderStatus.CANCEL_FAILED_WORKEXEC);
                order.setUpdatedBy(actor);
                salesOrderRepository.save(order);
                throw new IllegalStateException("Workorder cannot be cancelled: " + statusResult.nonCancellableReason());
            }

            WorkorderCancelResult cancelResult = workexecPort.cancelWorkorder(
                    command.workOrderId(),
                    new CancelWorkorderCommand(
                            orderId,
                        actor,
                            command.cancellationReason(),
                            idempotencyKey));

            if (!cancelResult.success()) {
                order.setStatus(SalesOrderStatus.CANCEL_FAILED_WORKEXEC);
                order.setUpdatedBy(actor);
                salesOrderRepository.save(order);
                throw new IllegalStateException("Workorder cancellation failed: " + cancelResult.resultMessage());
            }

            order.setStatus(SalesOrderStatus.WORKORDER_CANCELLED);
            order.setUpdatedBy(actor);
            salesOrderRepository.save(order);
        }

        if (command.paymentId() != null) {
            PaymentReversalResult reversalResult = billingPort.reversePayment(
                    command.paymentId(),
                    new ReversePaymentCommand(
                            "VOID",
                            null,
                            command.cancellationReason(),
                            orderId,
                            idempotencyKey));

            if (!reversalResult.success()) {
                order.setStatus(SalesOrderStatus.CANCEL_FAILED_BILLING);
                order.setUpdatedBy(actor);
                salesOrderRepository.save(order);
                throw new IllegalStateException("Payment reversal failed: " + reversalResult.resultMessage());
            }

            order.setStatus(SalesOrderStatus.PAYMENT_REVERSED);
            order.setUpdatedBy(actor);
            salesOrderRepository.save(order);
        }

        order.setStatus(SalesOrderStatus.CANCELLED);
        order.setUpdatedBy(actor);
        salesOrderRepository.save(order);

        return new CancellationResult(orderId, STATUS_CANCELLED, "Order cancelled successfully", idempotencyKey);
    }

    @Override
    @Transactional
    public @NonNull CancellationResult retryCancellation(@NonNull UUID orderId, @NonNull String idempotencyKey) {
        SalesOrder order = salesOrderRepository.findById(orderId)
                .orElseThrow(() -> new SalesOrderNotFoundException(orderId));
        String actor = SecurityContextHelper.getCurrentUsernameOrDefault("system");

        if (order.getStatus() != SalesOrderStatus.CANCEL_FAILED_BILLING) {
            throw new IllegalStateException(
                    "Order retry cancellation only allowed from CANCEL_FAILED_BILLING state: " + order.getStatus());
        }

        PaymentReversalResult reversalResult = billingPort.reversePayment(
                order.getPaymentId(),
                new ReversePaymentCommand("VOID", null, null, orderId, idempotencyKey));

        if (!reversalResult.success()) {
            order.setStatus(SalesOrderStatus.CANCEL_FAILED_BILLING);
            order.setUpdatedBy(actor);
            salesOrderRepository.save(order);
            throw new IllegalStateException("Payment reversal retry failed: " + reversalResult.resultMessage());
        }

        order.setStatus(SalesOrderStatus.PAYMENT_REVERSED);
        order.setUpdatedBy(actor);
        salesOrderRepository.save(order);

        order.setStatus(SalesOrderStatus.CANCELLED);
        order.setUpdatedBy(actor);
        salesOrderRepository.save(order);

        return new CancellationResult(orderId, STATUS_CANCELLED, "Order cancellation completed on retry", idempotencyKey);
    }
}
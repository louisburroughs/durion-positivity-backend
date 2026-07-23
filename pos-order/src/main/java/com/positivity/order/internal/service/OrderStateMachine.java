package com.positivity.order.internal.service;

import com.positivity.order.internal.entity.OrderStatusHistory;
import com.positivity.order.internal.entity.SalesOrder;
import com.positivity.order.internal.entity.SalesOrderStatus;
import com.positivity.order.internal.exception.InvalidOrderStateTransitionException;
import com.positivity.order.internal.exception.OrderNotEditableException;
import com.positivity.order.internal.repository.OrderStatusHistoryRepository;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Single authority for {@link SalesOrderStatus} transitions (spec §2.1, plan story A1).
 *
 * <p>Every status change must go through {@link #transition}; it validates the move against the
 * allowed-transition map, applies it, and appends an {@link OrderStatusHistory} row in the caller's
 * transaction. Callers remain responsible for persisting the order itself.
 */
@Component
@RequiredArgsConstructor
public class OrderStateMachine {

    private static final Map<SalesOrderStatus, Set<SalesOrderStatus>> ALLOWED = Map.ofEntries(
            Map.entry(
                    SalesOrderStatus.DRAFT,
                    Set.of(
                            SalesOrderStatus.QUOTED,
                            SalesOrderStatus.PENDING_PAYMENT,
                            SalesOrderStatus.CANCEL_REQUESTED)),
            Map.entry(
                    SalesOrderStatus.QUOTED,
                    Set.of(
                            SalesOrderStatus.DRAFT,
                            SalesOrderStatus.PENDING_PAYMENT,
                            SalesOrderStatus.CANCEL_REQUESTED)),
            Map.entry(
                    SalesOrderStatus.PENDING_PAYMENT,
                    Set.of(SalesOrderStatus.COMPLETED, SalesOrderStatus.VOIDED, SalesOrderStatus.CANCEL_REQUESTED)),
            Map.entry(
                    SalesOrderStatus.CANCEL_REQUESTED,
                    Set.of(
                            SalesOrderStatus.WORKORDER_CANCELLED,
                            SalesOrderStatus.PAYMENT_REVERSED,
                            SalesOrderStatus.CANCELLED,
                            SalesOrderStatus.CANCEL_FAILED_WORKEXEC,
                            SalesOrderStatus.CANCEL_FAILED_BILLING)),
            Map.entry(
                    SalesOrderStatus.WORKORDER_CANCELLED,
                    Set.of(
                            SalesOrderStatus.PAYMENT_REVERSED,
                            SalesOrderStatus.CANCELLED,
                            SalesOrderStatus.CANCEL_FAILED_BILLING)),
            Map.entry(SalesOrderStatus.PAYMENT_REVERSED, Set.of(SalesOrderStatus.CANCELLED)),
            Map.entry(
                    SalesOrderStatus.CANCEL_FAILED_WORKEXEC,
                    Set.of(SalesOrderStatus.CANCEL_REQUESTED, SalesOrderStatus.CANCEL_REQUIRES_MANUAL_REVIEW)),
            Map.entry(
                    SalesOrderStatus.CANCEL_FAILED_BILLING,
                    Set.of(
                            SalesOrderStatus.CANCEL_REQUESTED,
                            SalesOrderStatus.PAYMENT_REVERSED,
                            SalesOrderStatus.CANCEL_REQUIRES_MANUAL_REVIEW)),
            Map.entry(SalesOrderStatus.CANCEL_REQUIRES_MANUAL_REVIEW, Set.of(SalesOrderStatus.CANCEL_REQUESTED)),
            Map.entry(SalesOrderStatus.COMPLETED, Set.of()),
            Map.entry(SalesOrderStatus.VOIDED, Set.of()),
            Map.entry(SalesOrderStatus.CANCELLED, Set.of()));

    private final OrderStatusHistoryRepository historyRepository;
    private final Clock clock;

    /** True when {@code from → to} is an allowed transition. */
    public boolean isAllowed(@NonNull SalesOrderStatus from, @NonNull SalesOrderStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * Validate and apply a status transition, appending a history row. Does not save the order.
     *
     * @throws InvalidOrderStateTransitionException when the transition is not allowed
     */
    public void transition(@NonNull SalesOrder order, @NonNull SalesOrderStatus to, String reason) {
        SalesOrderStatus from = order.getStatus();
        if (from == null || !isAllowed(from, to)) {
            throw new InvalidOrderStateTransitionException(order.getOrderId(), from, to);
        }
        order.setStatus(to);
        recordHistory(order, from, to, reason);
    }

    /** Record the initial DRAFT entry for a newly created cart. */
    public void recordCreation(@NonNull SalesOrder order) {
        recordHistory(order, null, order.getStatus(), "cart created");
    }

    /**
     * Guard for cart mutations: line and source edits are only allowed while the order is DRAFT
     * (spec R2.5).
     *
     * @throws OrderNotEditableException when the order is in any other status
     */
    public void requireEditable(@NonNull SalesOrder order) {
        if (order.getStatus() != SalesOrderStatus.DRAFT) {
            throw new OrderNotEditableException(order.getOrderId(), order.getStatus());
        }
    }

    private void recordHistory(SalesOrder order, SalesOrderStatus from, SalesOrderStatus to, String reason) {
        historyRepository.save(OrderStatusHistory.builder()
                .orderId(order.getOrderId())
                .fromStatus(from)
                .toStatus(to)
                .actor(SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                .reason(reason)
                .transitionedAt(Instant.now(clock))
                .build());
    }
}

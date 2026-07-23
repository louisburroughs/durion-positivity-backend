package com.positivity.order.internal.exception;

import com.positivity.order.internal.entity.SalesOrderStatus;
import java.util.UUID;

/** Thrown when a status transition is requested that the order state machine does not allow. */
public class InvalidOrderStateTransitionException extends RuntimeException {

    public InvalidOrderStateTransitionException(UUID orderId, SalesOrderStatus from, SalesOrderStatus to) {
        super("Order " + orderId + " cannot transition from " + from + " to " + to);
    }
}

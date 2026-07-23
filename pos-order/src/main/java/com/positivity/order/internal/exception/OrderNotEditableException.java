package com.positivity.order.internal.exception;

import com.positivity.order.internal.entity.SalesOrderStatus;
import java.util.UUID;

/** Thrown when a cart mutation is attempted on an order that is no longer in an editable state. */
public class OrderNotEditableException extends RuntimeException {

    public OrderNotEditableException(UUID orderId, SalesOrderStatus status) {
        super("Order " + orderId + " cannot be modified in status " + status + "; mutations require DRAFT");
    }
}

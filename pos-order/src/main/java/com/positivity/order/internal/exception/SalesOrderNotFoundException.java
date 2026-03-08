package com.positivity.order.internal.exception;

import java.util.UUID;

public class SalesOrderNotFoundException extends RuntimeException {

    public SalesOrderNotFoundException(String message) {
        super(message);
    }

    public SalesOrderNotFoundException(UUID orderId) {
        super("Sales order not found with ID: " + orderId);
    }
}

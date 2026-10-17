package com.positivity.order.internal.exception;

/**
 * The order's state as pos-order projects it disagrees with the authority it just consulted (ADR-0067 A8, #2662):
 * pos-invoice refused a cash tender because its open balance differs from the remainder pos-order computed from
 * the payment facts it has seen so far. Answered 409 {@code ORDER_CONFLICT}; the caller re-reads the order and
 * retries with fresh figures.
 */
public class OrderConflictException extends RuntimeException {
    public OrderConflictException(String message) {
        super(message);
    }
}

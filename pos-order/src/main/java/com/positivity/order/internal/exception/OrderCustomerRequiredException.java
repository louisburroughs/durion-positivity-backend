package com.positivity.order.internal.exception;

/**
 * Checkout was asked for a cart that names no customer (CAP:550 S8, decision AW12): every sale
 * reaches the invoice and the ledger with a real party, so the cashier picks a registered customer
 * or chooses Walk-in explicitly. Maps to {@code 422 ORDER_CUSTOMER_REQUIRED}.
 */
public class OrderCustomerRequiredException extends RuntimeException {

    public OrderCustomerRequiredException() {
        super("Choose a customer before taking payment");
    }
}

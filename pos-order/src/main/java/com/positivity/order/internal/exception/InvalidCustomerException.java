package com.positivity.order.internal.exception;

/** Thrown when a supplied customer or vehicle reference is malformed or does not exist in CRM. */
public class InvalidCustomerException extends RuntimeException {

    public InvalidCustomerException(String message) {
        super(message);
    }
}

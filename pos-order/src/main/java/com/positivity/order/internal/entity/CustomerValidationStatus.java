package com.positivity.order.internal.entity;

/**
 * CRM validation state of the order's customer/vehicle references (plan story I2, resolved Q8).
 *
 * <p>{@code PENDING} lets cart work proceed while pos-customer is unreachable; financially
 * consequential transitions that require a validated customer must hard-block until it resolves
 * to {@code VALIDATED}.
 */
public enum CustomerValidationStatus {
    VALIDATED,
    PENDING
}

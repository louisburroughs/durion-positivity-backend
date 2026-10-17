package com.positivity.invoice.internal.enums;

/** How a payment intent was settled (ADR-0067 A8, #2662): a card capture through the gateway, or cash at the register. */
public enum PaymentMethodType {
    CARD,
    CASH
}

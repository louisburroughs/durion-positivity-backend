package com.positivity.accounting.internal.enums;

import java.util.Set;

/**
 * Which path created a {@code PaymentApplication} (CAP:550 S2, #2503; spec §7.1 "Automatic
 * application"). Set once at creation, never changed. Stored as its name in {@code
 * payment_application.application_source}.
 */
public enum ApplicationSource {

    /** A person applied the payment through {@code POST /v1/accounting/payments/{paymentId}/applications}. */
    MANUAL,

    /** Applied automatically when the payment settled ({@code payment.payment.settled}, AW14). */
    PAYMENT_SETTLED,

    /** Applied by the {@code INVOICE_PAYMENT} accounting-event processor (#2435). */
    INVOICE_PAYMENT;

    /** The sources nobody chose by hand: what "Matched automatically" lists. */
    public static final Set<ApplicationSource> AUTOMATIC = Set.of(PAYMENT_SETTLED, INVOICE_PAYMENT);
}

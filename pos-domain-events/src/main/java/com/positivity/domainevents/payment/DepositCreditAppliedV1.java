package com.positivity.domainevents.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Fact: a deposit / down-payment credit was drawn down against a settlement invoice (issue
 * #1621).
 *
 * <p>Published by pos-invoice on {@code payment.events.v1} with
 * {@code eventType = "payment.deposit-credit.applied"}. pos-accounting replicates these rows to
 * feed the collections-analytics {@code nonCashSettled} figure (issue #1621, ADR-0044 R3 replica
 * {@code ext_invoice_deposit_credit_application}).
 *
 * @param depositCreditId the deposit credit drawn down
 * @param invoiceId the settlement invoice the credit was applied to
 * @param amountApplied the amount drawn from the credit in this application
 * @param appliedAt when the draw-down committed
 */
public record DepositCreditAppliedV1(
        @NonNull UUID depositCreditId,
        @NonNull UUID invoiceId,
        @NonNull BigDecimal amountApplied,
        @NonNull Instant appliedAt) {

    public static final String EVENT_TYPE = "payment.deposit-credit.applied";
    public static final int SCHEMA_VERSION = 1;
}

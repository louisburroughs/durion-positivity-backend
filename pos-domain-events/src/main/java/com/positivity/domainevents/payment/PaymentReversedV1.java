package com.positivity.domainevents.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a payment against an invoice was reversed — an authorization voided or captured funds
 * refunded (order parity plan gate V1, story C3; spec R4.3).
 *
 * <p>Published by pos-invoice on {@code payment.events.v1} with
 * {@code eventType = "payment.payment.reversed"}. Consumers that track settled money (pos-order's
 * payment records) apply REFUND reversals against the matching settled payment; a VOID of a
 * never-captured authorization has no settled counterpart and is informational for them.
 *
 * @param paymentIntentId reversed payment intent; null for standalone refunds with no gateway leg
 * @param refundId refund record id; null for authorization voids
 * @param invoiceId invoice the payment belonged to; null for party-anchored standalone refunds
 * @param orderId sales order that fronted the invoice, when created via the from-order path
 * @param partyId bill-to party id string (ID only)
 * @param reversalType VOID (authorization released) or REFUND (captured funds returned)
 * @param amount reversed amount
 * @param currencyCode ISO-4217, USD platform-wide
 * @param reasonCode void/refund reason code
 * @param reversedAt when the reversal committed
 */
public record PaymentReversedV1(
        @Nullable UUID paymentIntentId,
        @Nullable UUID refundId,
        @Nullable UUID invoiceId,
        @Nullable UUID orderId,
        @Nullable String partyId,
        @NonNull String reversalType,
        @NonNull BigDecimal amount,
        @NonNull String currencyCode,
        @Nullable String reasonCode,
        @NonNull Instant reversedAt) {

    public static final String EVENT_TYPE = "payment.payment.reversed";
    public static final int SCHEMA_VERSION = 1;
}

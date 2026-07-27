package com.positivity.domainevents.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a payment against an invoice settled — funds were captured (order parity plan gate V1,
 * story C3; spec R4.2).
 *
 * <p>Published by pos-invoice on {@code payment.events.v1} with
 * {@code eventType = "payment.payment.settled"} when a {@code PaymentIntent} reaches CAPTURED
 * (immediate sale-capture or manual capture of an authorization). This is the per-payment
 * settlement fact pos-order's completion handshake consumes; the processor payout batch report
 * remains {@link SettlementReportedV1}.
 *
 * @param paymentIntentId settled payment intent (also the envelope aggregateId)
 * @param invoiceId invoice the payment was taken against
 * @param invoiceNumber human-facing invoice number
 * @param orderId sales order that fronted the invoice, when created via the from-order path
 * @param workorderId workorder backing the invoice, when workorder-sourced
 * @param partyId bill-to party id string (ID only — no embedded PII)
 * @param methodType settlement method summary: CASH / CARD / ON_ACCOUNT / OTHER (gateway
 *     captures report CARD)
 * @param amount captured amount
 * @param currencyCode ISO-4217, USD platform-wide
 * @param gatewayProvider processing gateway, when gateway-settled
 * @param gatewayReference processor reference, when gateway-settled
 * @param settledAt when the capture committed
 */
public record PaymentSettledV1(
        @NonNull UUID paymentIntentId,
        @NonNull UUID invoiceId,
        @Nullable String invoiceNumber,
        @Nullable UUID orderId,
        @Nullable UUID workorderId,
        @Nullable String partyId,
        @NonNull String methodType,
        @NonNull BigDecimal amount,
        @NonNull String currencyCode,
        @Nullable String gatewayProvider,
        @Nullable String gatewayReference,
        @NonNull Instant settledAt) {

    public static final String EVENT_TYPE = "payment.payment.settled";
    public static final int SCHEMA_VERSION = 1;
}

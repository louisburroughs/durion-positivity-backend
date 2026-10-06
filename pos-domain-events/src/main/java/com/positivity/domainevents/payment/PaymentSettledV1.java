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
 * <p><b>Schema version 2 (CAP:550 S9, spec §4.4 item 1, AW12).</b> {@code partyId} is always
 * present: pos-invoice refuses to finalise or take a payment on an invoice without a customer, so
 * every fact published at version 2 names the party. The bump is in place on
 * {@code payment.events.v1} under ADR-0044 §3 "Event contract standard" (precedent:
 * {@code catalog.service.updated}, {@code ProductUpdatedV1}), not a new {@code .v2} topic: §3
 * allows only additive changes within a topic version, and this change is non-breaking because it
 * adds no field and only tightens a guarantee — every version-2 message is a valid version-1
 * message, so a consumer built for version 1 reads it unchanged. The record deliberately keeps
 * <em>no</em> compact-constructor rejection of a null party: version-1 messages published before
 * go-live may carry {@code "partyId": null} and must still deserialise on redelivery or replay
 * (AW13 — earlier facts stay as they are). Today's consumers keep their defensive null handling
 * and do not read {@code schemaVersion}; telling a legacy null from a version-2 defect by the
 * envelope's {@code schemaVersion} is S11's accounting alert (#2508).
 *
 * @param paymentIntentId settled payment intent (also the envelope aggregateId)
 * @param invoiceId invoice the payment was taken against
 * @param invoiceNumber human-facing invoice number
 * @param orderId sales order that fronted the invoice, when created via the from-order path
 * @param workorderId workorder backing the invoice, when workorder-sourced
 * @param partyId bill-to party id string (ID only — no embedded PII); always present from schema
 *     version 2; version 1 messages published before go-live may carry null
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
        @NonNull String partyId,
        @NonNull String methodType,
        @NonNull BigDecimal amount,
        @NonNull String currencyCode,
        @Nullable String gatewayProvider,
        @Nullable String gatewayReference,
        @NonNull Instant settledAt) {

    public static final String EVENT_TYPE = "payment.payment.settled";
    public static final int SCHEMA_VERSION = 2;
}

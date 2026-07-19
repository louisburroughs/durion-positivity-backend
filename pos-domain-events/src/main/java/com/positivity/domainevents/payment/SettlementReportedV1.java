package com.positivity.domainevents.payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a payment processor reported a settlement (payout) for a closed batch of money movement
 * (plan {@code plan-odoo-parity-pos-accounting.md} §7 story F1a, decisions D-9…D-12).
 *
 * <p>Published by the payment/settlement adapter (pos-invoice {@code internal.settlement}, issue
 * #962) on {@code payment.events.v1} with {@code eventType = "payment.settlement.reported"}, one
 * fact per provider settlement, keyed by {@code settlementId}. This is a normalized,
 * processor-agnostic contract: any processor's payout model (Stripe payout/balance-transaction,
 * Adyen, Square, Braintree, ACH) is translated to this shape by the adapter, so pos-accounting
 * contains zero processor-specific code (decision D-5). Consumed by pos-accounting (issue #963,
 * story F1c) into {@code processor_settlement}/{@code processor_settlement_line} tables where lines
 * are matched to {@code ReceivablePayment}/{@code APPayment} and one batched journal entry is posted
 * per settlement.
 *
 * <h2>Invariants (frozen contract, decision D-10)</h2>
 *
 * <ul>
 *   <li><b>Header balance:</b> {@code grossAmount == feeAmount + netAmount} (validated by
 *       {@link BigDecimal#compareTo(BigDecimal)}, scale-independent).
 *   <li><b>Single currency per settlement:</b> the header {@code currency} governs every line;
 *       there is deliberately no per-line currency. The adapter emits one
 *       {@code SettlementReportedV1} per payout currency (decision D-14).
 *   <li><b>Correlation refs by line type:</b> {@code providerLineRef} and {@code paymentReference}
 *       are required on {@link LineType#CHARGE}, {@link LineType#REFUND} and
 *       {@link LineType#CHARGEBACK} lines (the lines that match to a platform payment), and optional
 *       on all other line types (decision D-10).
 *   <li><b>Per-line fee/net are nullable:</b> netted-per-line and header-only providers do not
 *       report line-level fee/net; matching always uses line {@code grossAmount}, never net
 *       (decision D-12).
 * </ul>
 *
 * <p>The payload carries its own {@code eventId}/{@code schemaVersion} so a persisted settlement row
 * is self-describing for replay and idempotency (decision D-10); these mirror the transport
 * {@link com.positivity.domainevents.DomainEventEnvelope} fields. New fields must be additive and
 * nullable within schema version 1 (ADR-0044 §3); a breaking change requires {@code V2} + a new
 * topic version.
 *
 * @param eventId unique UUIDv7 id of this settlement fact (idempotency key for consumers)
 * @param schemaVersion payload schema version ({@code >= 1})
 * @param settlementId provider-issued settlement/payout identifier; the aggregate id and Kafka key
 * @param providerCode payment processor code, e.g. {@code STRIPE} (matches provider config key)
 * @param settlementDate provider settlement (payout) date
 * @param currency ISO-4217 currency of the whole settlement, e.g. {@code USD}
 * @param grossAmount total gross moved in the settlement ({@code == feeAmount + netAmount})
 * @param feeAmount total processor fees withheld for the settlement
 * @param netAmount total net paid out to the platform bank account
 * @param lines per-transaction settlement lines
 * @param extension optional provider-raw fields carried through for audit; never business-critical
 */
public record SettlementReportedV1(
        @NonNull UUID eventId,
        int schemaVersion,
        @NonNull String settlementId,
        @NonNull String providerCode,
        @NonNull LocalDate settlementDate,
        @NonNull String currency,
        @NonNull BigDecimal grossAmount,
        @NonNull BigDecimal feeAmount,
        @NonNull BigDecimal netAmount,
        @NonNull List<Line> lines,
        @Nullable Map<String, String> extension) {

    public static final String EVENT_TYPE = "payment.settlement.reported";
    public static final int SCHEMA_VERSION = 1;

    /** ISO-4217 alphabetic currency code. */
    private static final Pattern CURRENCY_PATTERN = Pattern.compile("[A-Z]{3}");

    public SettlementReportedV1 {
        if (eventId == null) {
            throw new IllegalArgumentException("eventId must not be null");
        }
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be >= 1 but was: " + schemaVersion);
        }
        if (settlementId == null || settlementId.isBlank()) {
            throw new IllegalArgumentException("settlementId must not be blank");
        }
        if (providerCode == null || providerCode.isBlank()) {
            throw new IllegalArgumentException("providerCode must not be blank");
        }
        if (settlementDate == null) {
            throw new IllegalArgumentException("settlementDate must not be null");
        }
        if (currency == null || !CURRENCY_PATTERN.matcher(currency).matches()) {
            throw new IllegalArgumentException("currency must be an ISO-4217 code (e.g. USD) but was: " + currency);
        }
        if (grossAmount == null || feeAmount == null || netAmount == null) {
            throw new IllegalArgumentException("grossAmount, feeAmount and netAmount must not be null");
        }
        if (grossAmount.compareTo(feeAmount.add(netAmount)) != 0) {
            throw new IllegalArgumentException("header invariant violated: grossAmount (" + grossAmount
                    + ") must equal feeAmount (" + feeAmount + ") + netAmount (" + netAmount + ")");
        }
        lines = lines == null ? List.of() : List.copyOf(lines);
        extension = extension == null ? null : Map.copyOf(extension);
    }

    /**
     * Settlement line type (frozen enum, decision D-10). {@code PAYOUT} is intentionally excluded —
     * the settlement header <em>is</em> the payout, so a payout line would double-count. Consumers
     * (F1c) must route any {@code lineType} they cannot map to suspense, never mis-post.
     */
    public enum LineType {
        /** A customer charge settling to accounts receivable; correlation refs required. */
        CHARGE,
        /** A refund to a customer; correlation refs required. */
        REFUND,
        /** A disputed charge with its own dispute fee and disputed receivable; refs required. */
        CHARGEBACK,
        /** A standalone processor fee not itemized against a specific transaction. */
        FEE,
        /** A miscellaneous processor adjustment. */
        ADJUSTMENT,
        /** Provider placed funds on a restricted reserve hold (asset reclass, provider-gated). */
        RESERVE_HOLD,
        /** Provider released a prior reserve hold (asset reclass, provider-gated). */
        RESERVE_RELEASE,
        /** A correction to a prior payout. */
        PAYOUT_CORRECTION
    }

    /**
     * One settlement line.
     *
     * <p>{@code providerLineRef} and {@code paymentReference} are required on {@link LineType#CHARGE},
     * {@link LineType#REFUND} and {@link LineType#CHARGEBACK} (they carry the platform-side
     * correlation token the processor echoed — {@code ReceivablePayment.sourceEventId} for AR,
     * {@code APPayment.paymentRef} for AP, matched on gross, decision D-11) and optional otherwise.
     * {@code feeAmount}/{@code netAmount} are nullable because netted-per-line and header-only
     * providers do not report them per line (decision D-12).
     *
     * @param providerLineRef provider's own line reference; required on CHARGE/REFUND/CHARGEBACK
     * @param lineType settlement line type
     * @param grossAmount line gross amount (the amount matched against a platform payment)
     * @param feeAmount line-level fee, if the provider itemizes fees per line; else null
     * @param netAmount line-level net, if the provider itemizes net per line; else null
     * @param paymentReference platform correlation token; required on CHARGE/REFUND/CHARGEBACK
     */
    public record Line(
            @Nullable String providerLineRef,
            @NonNull LineType lineType,
            @NonNull BigDecimal grossAmount,
            @Nullable BigDecimal feeAmount,
            @Nullable BigDecimal netAmount,
            @Nullable String paymentReference) {

        public Line {
            if (lineType == null) {
                throw new IllegalArgumentException("lineType must not be null");
            }
            if (grossAmount == null) {
                throw new IllegalArgumentException("grossAmount must not be null");
            }
            if (requiresCorrelationRefs(lineType)) {
                if (providerLineRef == null || providerLineRef.isBlank()) {
                    throw new IllegalArgumentException("providerLineRef is required for lineType " + lineType);
                }
                if (paymentReference == null || paymentReference.isBlank()) {
                    throw new IllegalArgumentException("paymentReference is required for lineType " + lineType);
                }
            }
        }

        /** True for the line types that match to a platform payment and so require correlation refs. */
        public static boolean requiresCorrelationRefs(@NonNull LineType lineType) {
            return lineType == LineType.CHARGE || lineType == LineType.REFUND || lineType == LineType.CHARGEBACK;
        }
    }
}

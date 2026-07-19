package com.positivity.domainevents.payment;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Per-provider settlement matching configuration (plan {@code plan-odoo-parity-pos-accounting.md}
 * §7 story F1a, decision D-9).
 *
 * <p>Authored and owned by the payment service (pos-invoice settlement adapter, issue #962) and
 * delivered to pos-accounting as a <b>compacted</b> event on a config topic
 * ({@code payment.settlement-config.v1}) keyed by {@code providerCode} with last-writer-wins
 * semantics. Accounting consumes it into a read-only {@code ext_payment_settlement_config} replica
 * (ADR-0044 §3: one owner, slowly-changing fact referenced on every settlement) — never embedded as
 * per-{@link SettlementReportedV1} metadata (that would denormalize and risk mid-stream drift,
 * decision D-9). Because the topic is compacted and keyed by {@code providerCode}, each record is
 * the full current configuration for that provider; there is no partial/delta update.
 *
 * <p>All non-identity fields are additive and the replica tolerates unknown fields, so schema
 * version 1 may grow additively (ADR-0044 §3); a breaking change requires {@code V2} + a new topic
 * version.
 *
 * @param eventId unique UUIDv7 id of this config revision (idempotency/audit key)
 * @param schemaVersion payload schema version ({@code >= 1})
 * @param providerCode payment processor code; the compaction key and identity, e.g. {@code STRIPE}
 * @param providerName human-readable provider name; audit/display only, may be null
 * @param matchReferenceField which correlation reference accounting matches settlement lines on
 * @param amountTolerance non-negative absolute amount tolerance for matching a line to a payment
 * @param feeRepresentation how the provider reports fees across the settlement (posting concern)
 * @param extension optional provider-raw config fields carried through for audit
 */
public record SettlementProviderConfigV1(
        @NonNull UUID eventId,
        int schemaVersion,
        @NonNull String providerCode,
        @Nullable String providerName,
        @NonNull MatchReferenceField matchReferenceField,
        @NonNull BigDecimal amountTolerance,
        @NonNull FeeRepresentation feeRepresentation,
        @Nullable Map<String, String> extension) {

    public static final String EVENT_TYPE = "payment.settlement-config.upserted";
    public static final int SCHEMA_VERSION = 1;

    public SettlementProviderConfigV1 {
        if (eventId == null) {
            throw new IllegalArgumentException("eventId must not be null");
        }
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be >= 1 but was: " + schemaVersion);
        }
        if (providerCode == null || providerCode.isBlank()) {
            throw new IllegalArgumentException("providerCode must not be blank");
        }
        if (matchReferenceField == null) {
            throw new IllegalArgumentException("matchReferenceField must not be null");
        }
        if (amountTolerance == null || amountTolerance.signum() < 0) {
            throw new IllegalArgumentException("amountTolerance must be non-negative but was: " + amountTolerance);
        }
        if (feeRepresentation == null) {
            throw new IllegalArgumentException("feeRepresentation must not be null");
        }
        extension = extension == null ? null : Map.copyOf(extension);
    }

    /**
     * The platform-side correlation reference a settlement line is matched on (decision D-11).
     * Matching always compares on line gross, never net (decision D-12).
     */
    public enum MatchReferenceField {
        /** Match {@link SettlementReportedV1.Line#paymentReference()} to the platform payment token. */
        PAYMENT_REFERENCE,
        /** Match {@link SettlementReportedV1.Line#providerLineRef()} to a stored provider reference. */
        PROVIDER_LINE_REF
    }

    /**
     * How the provider reports fees across a settlement (decision D-12). Regardless of mode,
     * accounting posts one {@code Dr Processor Fees = header.feeAmount}; the mode only tells the
     * consumer whether line-level fee/net fields are populated.
     */
    public enum FeeRepresentation {
        /** Fees arrive as their own {@link SettlementReportedV1.LineType#FEE} lines. */
        SEPARATE_LINES,
        /** Each line carries its own net (gross minus fee) via the nullable line fee/net fields. */
        NETTED_PER_LINE,
        /** Fees are reported only at the settlement header; lines carry no fee/net. */
        HEADER_ONLY
    }
}

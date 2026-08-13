package com.positivity.supplier.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One recorded read of an exchange payload — ADR-0050 §7's "who read which exchange, when".
 *
 * <p>The subject is taken from the authenticated security context, never from anything the caller
 * supplied: an actor a caller could choose is not an audit record, it is a claim.
 *
 * @param auditAccessId identity of the access record
 * @param exchangeAuditId the exchange whose content was read
 * @param vendorProfileId owning vendor profile of that exchange, denormalised so the row stays
 *     interpretable on its own
 * @param capability capability of that exchange, denormalised for the same reason
 * @param accessedBy the authenticated subject that performed the read
 * @param accessedAt when the read happened
 * @param accessKind what was reached; only payload reads are recorded
 * @param payloadOutcome what the read yielded — including the failed and empty cases
 * @param correlationId correlation id of the request that caused the read; {@code null} only on rows
 *     recorded before inbound correlation existed (pre-V6)
 */
@Schema(description = "One recorded read of an exchange payload: who read which exchange, when (ADR-0050 §7).")
public record ExchangeAuditAccessView(
        @Schema(
                description = "Identity of this access record (UUIDv7).",
                example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a80")
        @NonNull
        UUID auditAccessId,

        @Schema(
                description = "The exchange-audit row whose content was read.",
                example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a70")
        @NonNull
        UUID exchangeAuditId,

        @Schema(
                description = "Owning vendor profile of the exchange that was read.",
                example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b")
        @NonNull
        UUID vendorProfileId,

        @Schema(description = "Canonical capability key of the exchange that was read.", example = "STOCK_INQUIRY")
        @NonNull
        String capability,

        @Schema(
                description = "The authenticated subject that performed the read. Taken from the security context,"
                        + " never from the request body or a query parameter.",
                example = "a.operator@example.com")
        @NonNull
        String accessedBy,

        @Schema(description = "When the read happened.", example = "2026-08-11T14:22:07.412Z") @NonNull
        Instant accessedAt,

        @Schema(description = "What the caller reached. Only payload reads are recorded.", example = "PAYLOAD_READ")
        @NonNull
        AuditAccessKind accessKind,

        @Schema(description = "What the read yielded, including the failed and empty cases.", example = "DECRYPTED")
        @NonNull
        AuditPayloadOutcome payloadOutcome,

        @Schema(
                description = "Correlation id of the request that caused the read, reused from the caller's"
                        + " X-Correlation-Id header (or generated when absent). Null only for access records"
                        + " written before inbound correlation existed.",
                example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a90")
        @Nullable
        String correlationId) {

    public ExchangeAuditAccessView {
        Objects.requireNonNull(auditAccessId, "auditAccessId must not be null");
        Objects.requireNonNull(exchangeAuditId, "exchangeAuditId must not be null");
        Objects.requireNonNull(vendorProfileId, "vendorProfileId must not be null");
        Objects.requireNonNull(capability, "capability must not be null");
        Objects.requireNonNull(accessedBy, "accessedBy must not be null");
        Objects.requireNonNull(accessedAt, "accessedAt must not be null");
        Objects.requireNonNull(accessKind, "accessKind must not be null");
        Objects.requireNonNull(payloadOutcome, "payloadOutcome must not be null");
    }
}

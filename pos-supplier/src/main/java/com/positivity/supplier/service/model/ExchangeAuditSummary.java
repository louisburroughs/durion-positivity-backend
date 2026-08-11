package com.positivity.supplier.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One outbound supplier <em>attempt</em> without its payloads (ADR-0050 §7).
 *
 * <p>Metadata only, deliberately: reading commercial payload content requires a second, individually
 * audited call ({@code GET /v1/supplier/admin/audit/exchanges/{id}/payload}). Listing exchanges
 * therefore discloses nothing that needs recording, and — because the metadata query never touches the
 * encrypted columns — a row whose payload is unreadable still lists normally, which is exactly when an
 * operator most needs to see it.
 *
 * <p>Canonical keys are strings, not enums, mirroring the rest of this module's contract (ADR-0026):
 * the internal {@code SupplierCapability} / {@code ProtocolFamily} enums must not leak.
 *
 * @param exchangeAuditId identity of the audit row; the key for the payload and access endpoints
 * @param vendorProfileId owning vendor profile. Present even when that profile has since been deleted:
 *     the audit table deliberately has no foreign key, so history outlives configuration
 * @param supplierRef the profile's alias <strong>as at the time of the exchange</strong>. A snapshot,
 *     never a lookup key — profiles are renameable, so this may not match the profile's current ref
 * @param bindingId endpoint binding used; may be {@code null}
 * @param capability canonical capability key exercised
 * @param protocolFamily canonical protocol family key of the adapter used
 * @param protocolVersion norm-version key within the family (ADR-0051 §3)
 * @param httpMethod request method
 * @param endpointUri absolute request URI. Credentials travel in headers, never in the URI
 * @param attempt 1-based attempt number within one logical call, so retries are individually visible
 * @param correlationId groups every attempt of one logical call; use it to trace a retry sequence
 * @param outcome ADR-0052 §5 classification of this attempt
 * @param httpStatus response status; {@code null} when no response was received at all
 * @param startedAt when the attempt began
 * @param durationMs how long the attempt took
 * @param failureDetail operator-facing failure summary; {@code null} on success, never a credential
 * @param captureLevel the capture level actually applied to this row — recorded rather than re-derived,
 *     because the binding's level can change afterwards
 * @param requestPayloadPresent whether a request payload is currently stored for this row
 * @param responsePayloadPresent whether a response payload is currently stored for this row
 * @param payloadsPurgedAt when retention nulled the payloads; distinguishes "purged" from "never
 *     captured"
 * @param createdBy audit actor (ADR-0018); the system actor for scheduler-driven exchanges
 */
@Schema(
        description = "One outbound supplier attempt, without payload content. Reading the payload is a separate,"
                + " individually audited call.")
public record ExchangeAuditSummary(
        @Schema(
                description = "Identity of this exchange-audit row (UUIDv7).",
                example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a70")
        @NonNull
        UUID exchangeAuditId,

        @Schema(
                description = "Owning vendor profile. Still populated when that profile has since been deleted --"
                        + " exchange history deliberately outlives configuration (ADR-0050 §7).",
                example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b")
        @NonNull
        UUID vendorProfileId,

        @Schema(
                description = "The profile's alias AS AT THE TIME OF THE EXCHANGE. A descriptive snapshot, never a"
                        + " lookup key: profiles are renameable, so this may differ from the profile's current ref.",
                example = "michelin-eu")
        @NonNull
        String supplierRef,

        @Schema(
                description = "Endpoint binding used for this attempt, when one was involved.",
                example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a60")
        @Nullable
        UUID bindingId,

        @Schema(description = "Canonical supplier capability key exercised.", example = "STOCK_INQUIRY") @NonNull
        String capability,

        @Schema(description = "Canonical protocol family key of the adapter used.", example = "EDIWHEEL_A25") @NonNull
        String protocolFamily,

        @Schema(description = "Adapter version key within the protocol family.", example = "A2_5") @NonNull
        String protocolVersion,

        @Schema(description = "HTTP method of the attempt.", example = "POST") @NonNull
        String httpMethod,

        @Schema(
                description = "Absolute request URI. Credentials travel in headers and never appear here.",
                example = "https://edi.michelin.example/a25/stock/inquiry")
        @NonNull
        String endpointUri,

        @Schema(
                description = "1-based attempt number within one logical call. Retries are separate rows sharing a"
                        + " correlation id, so a sequence of 1,2,3 is one call retried twice.",
                example = "2")
        int attempt,

        @Schema(
                description = "Groups every attempt of one logical call. Use it to trace a retry sequence.",
                example = "018f0a1b2c3d7e4f8a9b0c1d2e3f4a71")
        @NonNull
        String correlationId,

        @Schema(description = "Classification of the attempt (ADR-0052 §5).", example = "TRANSPORT_TIMEOUT") @NonNull
        String outcome,

        @Schema(
                description = "Response status code. Null when no response was received at all -- a connect failure,"
                        + " a timeout before headers, or an attempt suppressed by an open circuit breaker.",
                example = "200")
        @Nullable
        Integer httpStatus,

        @Schema(description = "When the attempt began.", example = "2026-08-11T09:14:02.117Z") @NonNull
        Instant startedAt,

        @Schema(description = "How long the attempt took, in milliseconds.", example = "1843")
        long durationMs,

        @Schema(
                description = "Operator-facing failure summary. Null on success; never contains a credential.",
                example = "Read timed out after 30000 ms")
        @Nullable
        String failureDetail,

        @Schema(
                description = "The payload capture level actually applied to THIS row (ADR-0050 §7). Recorded rather"
                        + " than re-derived, because the binding's level can change after the fact: a REDACTED row"
                        + " stays REDACTED even if the binding is later set to FULL.",
                example = "REDACTED")
        @NonNull
        PayloadCaptureLevel captureLevel,

        @Schema(
                description = "Whether a request payload is currently stored. Determined without decrypting anything.",
                example = "true")
        boolean requestPayloadPresent,

        @Schema(
                description = "Whether a response payload is currently stored. Determined without decrypting anything.",
                example = "true")
        boolean responsePayloadPresent,

        @Schema(
                description = "When retention nulled this row's payloads (ADR-0050 §7, 400 days). Distinguishes"
                        + " 'purged' from 'never captured': metadata is retained permanently, only content expires.",
                example = "2027-09-15T03:30:00Z")
        @Nullable
        Instant payloadsPurgedAt,

        @Schema(
                description = "Audit actor that caused the exchange (ADR-0018). The system actor for"
                        + " scheduler-driven exchanges, which have no security context.",
                example = "system:supplier-scheduler")
        @Nullable
        String createdBy) {

    public ExchangeAuditSummary {
        Objects.requireNonNull(exchangeAuditId, "exchangeAuditId must not be null");
        Objects.requireNonNull(vendorProfileId, "vendorProfileId must not be null");
        Objects.requireNonNull(supplierRef, "supplierRef must not be null");
        Objects.requireNonNull(capability, "capability must not be null");
        Objects.requireNonNull(protocolFamily, "protocolFamily must not be null");
        Objects.requireNonNull(protocolVersion, "protocolVersion must not be null");
        Objects.requireNonNull(httpMethod, "httpMethod must not be null");
        Objects.requireNonNull(endpointUri, "endpointUri must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(captureLevel, "captureLevel must not be null");
    }
}

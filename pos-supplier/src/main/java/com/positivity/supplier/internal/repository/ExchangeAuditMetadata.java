package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.enums.PayloadCaptureLevel;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Everything about an exchange-audit row <strong>except</strong> its payloads.
 *
 * <h2>Why this exists rather than loading the entity</h2>
 *
 * {@code ExchangeAuditEntity} maps both payload columns through {@code EncryptedPayloadConverter},
 * which runs <em>during entity hydration</em>. Loading entities to build a metadata listing would
 * therefore:
 *
 * <ol>
 *   <li>decrypt every payload on the page only to discard it — the same pointless work, and the same
 *       needless widening of where plaintext exists, that the retention purge deliberately avoids by
 *       being a bulk update;
 *   <li>let a <em>single</em> unreadable row (rotated-away key, tampered ciphertext) fail the whole
 *       listing, so the one situation an investigator most needs to see would be the one they cannot
 *       list; and
 *   <li>make it impossible to record an access with an honest outcome, because the identity needed to
 *       write the access row would live inside the load that just failed.
 * </ol>
 *
 * Every metadata query therefore selects into this record through an explicit JPQL constructor
 * expression. That is deliberately provable by inspection: the {@code SELECT} clause names the
 * columns, the payload columns are not among them, so the converter cannot run. It is not left to
 * framework projection optimisation, because "plaintext is not read here" is a security property and
 * must not depend on a query planner's discretion.
 *
 * @param exchangeAuditId row identity
 * @param vendorProfileId owning profile; the identity every query uses
 * @param supplierRef the profile's alias as at the time of the exchange — a snapshot, never a key
 * @param bindingId binding used; {@code null} only for a hypothetical binding-less flow
 * @param capability capability exercised
 * @param protocolFamily adapter family used
 * @param protocolVersion norm-version key within the family (ADR-0051 §3)
 * @param httpMethod request method
 * @param endpointUri absolute request URI; credentials travel in headers, never here
 * @param attempt 1-based attempt number within one logical call
 * @param correlationId groups the attempts of one logical call
 * @param outcome ADR-0052 §5 classification name
 * @param httpStatus response status; {@code null} when no response was received
 * @param startedAt when the attempt began
 * @param durationMs attempt duration
 * @param failureDetail operator-facing failure summary; never a credential
 * @param captureLevel the capture level actually applied to this row
 * @param requestPayloadPresent whether a request payload is stored — computed in SQL, so knowing it
 *     costs no decryption
 * @param responsePayloadPresent whether a response payload is stored
 * @param payloadsPurgedAt when the retention purge nulled the payloads; distinguishes "purged" from
 *     "never captured"
 * @param createdBy audit actor (ADR-0018); the system actor for scheduler-driven exchanges
 */
public record ExchangeAuditMetadata(
        @NonNull UUID exchangeAuditId,
        @NonNull UUID vendorProfileId,
        @NonNull String supplierRef,
        @Nullable UUID bindingId,
        @NonNull SupplierCapability capability,
        @NonNull ProtocolFamily protocolFamily,
        @NonNull String protocolVersion,
        @NonNull String httpMethod,
        @NonNull String endpointUri,
        int attempt,
        @NonNull String correlationId,
        @NonNull String outcome,
        @Nullable Integer httpStatus,
        @NonNull Instant startedAt,
        long durationMs,
        @Nullable String failureDetail,
        @NonNull PayloadCaptureLevel captureLevel,
        boolean requestPayloadPresent,
        boolean responsePayloadPresent,
        @Nullable Instant payloadsPurgedAt,
        @Nullable String createdBy) {}

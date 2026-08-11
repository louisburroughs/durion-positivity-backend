package com.positivity.supplier.internal.spi;

import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Everything observable about a single outbound attempt, handed to every
 * {@link ExchangeObserver} once per attempt — including failed attempts and each individual
 * retry, not just the final result. Slice 3's exchange-audit writer is an observer over this.
 *
 * <p>Identity is {@code vendorProfileId} (ADR-0050 §1), never {@code supplierRef}:
 * {@code supplierRef} is a renameable configuration alias, so it is carried alongside as a
 * descriptive snapshot only and must never be used as a join or filter key.
 *
 * <p><strong>Credential headers are never populated here at all</strong> — no {@code Authorization},
 * no API key, no resolved secret reaches this record, so every <em>scalar</em> field is safe to log.
 *
 * <p><strong>But {@code requestBody} and {@code responseBody} are RAW wire documents, exactly as
 * sent and received, with no redaction whatsoever.</strong> The base client does not redact and
 * makes no attempt to: it is format-agnostic and cannot know which element of an EDIWheel document
 * is sensitive. Applying the binding's {@code captureLevel}
 * ({@code FULL} / {@code REDACTED} / {@code METADATA_ONLY}) and any body-field redaction is
 * therefore the <strong>observer's obligation</strong>, before it persists or logs anything
 * (ADR-0050 §7).
 *
 * <p>An observer that logs {@code requestBody} verbatim, or persists it without consulting
 * {@code captureLevel}, leaks commercial payload. If a vendor ever carries a credential inside a
 * body element, this is where it would surface — so treat these two fields as untrusted content,
 * not as pre-sanitized text.
 *
 * @param vendorProfileId platform identity of the profile that owns the binding
 * @param supplierRef configuration alias at the time of the exchange; descriptive only
 * @param capability the supplier capability being exercised
 * @param protocolFamily adapter protocol family of the binding
 * @param protocolVersion adapter version within the family; data, not an enum (ADR-0051 §3)
 * @param bindingId identity of the endpoint binding used
 * @param method HTTP method
 * @param uri absolute request URI, query included, exactly as sent. This is the RAW value and it may well
 *     carry credentials -- a vendor taking an api key as a query parameter, or userinfo in a configured base
 *     URL. Redaction is the audit writer's job, not the caller's; observers must not persist this verbatim
 * @param attempt 1-based attempt number within one logical call
 * @param correlationId correlation id sent as {@code X-Correlation-Id}; never blank
 * @param outcome classification of this attempt (ADR-0052 §5)
 * @param httpStatus response status when a response was received; {@code null} when the attempt
 *     never got one (pre-send failure, configuration error)
 * @param startedAt when the attempt began
 * @param duration wall-clock duration of the attempt
 * @param requestBody the raw request payload as sent; NOT redacted — see the class javadoc
 * @param responseBody the raw response payload as received; NOT redacted — see the class javadoc
 * @param failureDetail operator-facing failure summary, raw. May quote a vendor response, including a
 *     redirect {@code Location} carrying a signed URL; the audit writer redacts embedded URL credentials
 */
public record ExchangeContext(
        @NonNull UUID vendorProfileId,
        @NonNull String supplierRef,
        @NonNull SupplierCapability capability,
        @NonNull ProtocolFamily protocolFamily,
        @NonNull String protocolVersion,
        @NonNull UUID bindingId,
        @NonNull String method,
        @NonNull String uri,
        int attempt,
        @NonNull String correlationId,
        @NonNull ExchangeOutcome outcome,
        @Nullable Integer httpStatus,
        @NonNull Instant startedAt,
        @NonNull Duration duration,
        @Nullable String requestBody,
        @Nullable String responseBody,
        @Nullable String failureDetail) {

    public ExchangeContext {
        Objects.requireNonNull(vendorProfileId, "vendorProfileId must not be null");
        Objects.requireNonNull(supplierRef, "supplierRef must not be null");
        Objects.requireNonNull(capability, "capability must not be null");
        Objects.requireNonNull(protocolFamily, "protocolFamily must not be null");
        Objects.requireNonNull(protocolVersion, "protocolVersion must not be null");
        Objects.requireNonNull(bindingId, "bindingId must not be null");
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(uri, "uri must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(duration, "duration must not be null");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1");
        }
        if (correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId must not be blank");
        }
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
    }
}

package com.positivity.supplier.internal.service;

import com.positivity.supplier.internal.audit.AuditActorContext;
import com.positivity.supplier.internal.entity.ExchangeAuditEntity;
import com.positivity.supplier.internal.entity.SupplierEndpointBindingEntity;
import com.positivity.supplier.internal.enums.PayloadCaptureLevel;
import com.positivity.supplier.internal.repository.ExchangeAuditRepository;
import com.positivity.supplier.internal.repository.SupplierEndpointBindingRepository;
import com.positivity.supplier.internal.spi.ExchangeContext;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one exchange-audit row in its own transaction (ADR-0050 §7).
 *
 * <h2>Why this is a separate bean, and not a method on {@link ExchangeAuditObserver}</h2>
 *
 * It used to be. {@code onExchange} called {@code persist(context)} on {@code this}, which does not go
 * through the Spring proxy, so the {@code REQUIRES_NEW} on it <strong>never took effect</strong>. Both
 * halves of the documented guarantee were false as a result, and the second is the dangerous one:
 *
 * <ol>
 *   <li>The audit write joined the caller's transaction, so when the caller rolled back the audit row went
 *       with it — losing exactly the record of the failed exchange an operator would go looking for.
 *   <li>A failing audit write marked the <em>caller's</em> transaction rollback-only. The observer's catch
 *       then swallowed the exception, the caller carried on believing it had succeeded, and the caller's
 *       commit blew up with {@code UnexpectedRollbackException} — i.e. an unavailable audit sink could fail
 *       real work, which is precisely what {@code REQUIRES_NEW} was there to prevent.
 * </ol>
 *
 * <p>Self-invocation defeating {@code @Transactional} is a well-known trap and it is invisible in review:
 * the annotation is present, spelled correctly, and does nothing.
 * {@code ExchangeAuditWriterTest.auditRowSurvivesARolledBackCallerTransaction} pins the real boundary from
 * the only place that can observe it — inside an outer transaction that is then rolled back.
 *
 * <p>Public, not package-private, for the same reason: a proxy can only intercept what a caller can reach
 * through it.
 */
@Service
public class ExchangeAuditWriter {

    private final ExchangeAuditRepository auditRepository;
    private final SupplierEndpointBindingRepository bindingRepository;
    private final PayloadCaptureLevel defaultCaptureLevel;

    public ExchangeAuditWriter(
            @NonNull ExchangeAuditRepository auditRepository,
            @NonNull SupplierEndpointBindingRepository bindingRepository,
            @Value("${pos.supplier.audit.default-capture-level:REDACTED}") String defaultCaptureLevel) {
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository");
        this.bindingRepository = Objects.requireNonNull(bindingRepository, "bindingRepository");
        // Defaults to REDACTED, not FULL: an unconfigured binding must not capture credentials that
        // happen to sit inside a vendor document. Fail safe, not fail open.
        this.defaultCaptureLevel = PayloadCaptureLevel.valueOf(defaultCaptureLevel);
    }

    /**
     * Persists one attempt.
     *
     * <p>{@code REQUIRES_NEW} so this neither joins nor poisons a caller's transaction — see the class
     * javadoc for what happened when that was true only on paper. Exceptions propagate from here on purpose;
     * isolating the caller from them is {@link ExchangeAuditObserver}'s job, and doing it in both places
     * would hide a broken audit sink twice over.
     *
     * @param context the attempt to record, carrying raw wire documents
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(@NonNull ExchangeContext context) {
        Objects.requireNonNull(context, "context must not be null");
        PayloadCaptureLevel captureLevel = resolveCaptureLevel(context.bindingId());
        // Redacted before truncation, and kept in a local so the truncate bound stays a simple, greppable
        // pair -- ExchangeAuditColumnWidthParityTest parses these bounds out of this file and compares them
        // against the migrations, and a nested call hid one of them the first time.
        String storedUri = PayloadRedactor.redactUri(context.uri(), captureLevel);
        // Same reason for the local as storedUri: the parity test parses these truncate bounds out of this
        // file, and it has now twice reported a column as unprotected because a nested call hid its bound.
        String storedFailureDetail = PayloadRedactor.redactEmbeddedUris(context.failureDetail());

        ExchangeAuditEntity row = ExchangeAuditEntity.builder()
                .vendorProfileId(context.vendorProfileId())
                .supplierRef(truncate(context.supplierRef(), 100))
                .bindingId(context.bindingId())
                .capability(context.capability())
                .protocolFamily(context.protocolFamily())
                .protocolVersion(context.protocolVersion())
                .httpMethod(context.method())
                // A metadata column: unencrypted, retained after the purge nulls the payloads, and returned
                // by the listing -- so anything left in it is kept indefinitely. Populated at every capture
                // level, so METADATA_ONLY additionally drops the query string outright: that level promises to
                // retain no content and query parameters are content. See PayloadRedactor.redactUri.
                .endpointUri(truncate(storedUri, 2048))
                .attempt(context.attempt())
                // Truncated because this is the one field a REMOTE party influences: it is reused from the
                // inbound X-Correlation-Id header. An oversized header would otherwise fail the insert and
                // silently cost the audit row -- a client-controlled value must not be able to delete
                // evidence, and a truncated correlation id still groups the attempts of one call.
                .correlationId(truncate(context.correlationId(), 100))
                .outcome(context.outcome().name())
                .httpStatus(context.httpStatus())
                .startedAt(context.startedAt())
                .durationMs(context.duration().toMillis())
                // Redacted for the same reason as the URI, and it is the same kind of value: this column
                // quotes vendor responses, and a redirect Location routinely carries a signed URL whose token
                // is a live bearer credential. Unencrypted, not covered by the payload purge, and returned by
                // the metadata listing.
                .failureDetail(truncate(storedFailureDetail, 2048))
                .captureLevel(captureLevel)
                // The load-bearing line: raw bodies are never persisted unredacted.
                .requestPayload(PayloadRedactor.applyCaptureLevel(context.requestBody(), captureLevel))
                .responsePayload(PayloadRedactor.applyCaptureLevel(context.responseBody(), captureLevel))
                .build();

        // Scheduler and client threads have no security context, so the system actor is supplied
        // explicitly and the save is flushed inside the scope -- @CreatedBy fires at flush time.
        AuditActorContext.withActor(ExchangeAuditObserver.SYSTEM_ACTOR, () -> {
            auditRepository.save(row);
            auditRepository.flush();
        });
    }

    /**
     * The capture level governing this exchange.
     *
     * <p>Read from the binding rather than carried on the context, so a level change applies to
     * subsequent exchanges immediately. When the binding cannot be read — it may have been deleted
     * between the exchange and this write — the configured default applies rather than
     * {@code FULL}: an unknown level must never widen capture.
     */
    @NonNull
    private PayloadCaptureLevel resolveCaptureLevel(@Nullable UUID bindingId) {
        if (bindingId == null) {
            return defaultCaptureLevel;
        }
        return bindingRepository
                .findById(bindingId)
                .map(SupplierEndpointBindingEntity::getCaptureLevel)
                .orElse(defaultCaptureLevel);
    }

    @Nullable
    private static String truncate(@Nullable String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}

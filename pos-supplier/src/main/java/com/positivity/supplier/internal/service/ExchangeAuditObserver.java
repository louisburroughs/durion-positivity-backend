package com.positivity.supplier.internal.service;

import com.positivity.supplier.internal.audit.AuditActorContext;
import com.positivity.supplier.internal.entity.ExchangeAuditEntity;
import com.positivity.supplier.internal.entity.SupplierEndpointBindingEntity;
import com.positivity.supplier.internal.enums.PayloadCaptureLevel;
import com.positivity.supplier.internal.repository.ExchangeAuditRepository;
import com.positivity.supplier.internal.repository.SupplierEndpointBindingRepository;
import com.positivity.supplier.internal.spi.ExchangeContext;
import com.positivity.supplier.internal.spi.ExchangeObserver;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists one row per outbound attempt (ADR-0050 §7). Replaces the no-op observer by being the only
 * {@link ExchangeObserver} bean in the context.
 *
 * <h2>Redaction is this class's obligation</h2>
 *
 * {@link ExchangeContext} carries <strong>raw wire documents</strong> — the base client is
 * format-agnostic and cannot know which element is sensitive. This observer applies the binding's
 * {@link PayloadCaptureLevel} through {@link PayloadRedactor} before anything is persisted. Getting
 * this wrong is unrecoverable in the sense that matters: a credential written into a 400-day store
 * cannot be un-written.
 *
 * <h2>Own transaction, and failures never propagate</h2>
 *
 * {@code REQUIRES_NEW} so an audit write neither joins nor poisons a caller's transaction, and the
 * whole method is guarded: ADR-0050 §7 wants the trail complete, but a successful vendor exchange must
 * not be reported as failed because the audit sink was unavailable. A failure to audit is logged at
 * ERROR — loudly, because a silent gap in a commercial audit trail is worse than a noisy log.
 */
@Component
public class ExchangeAuditObserver implements ExchangeObserver {

    private static final Logger log = LoggerFactory.getLogger(ExchangeAuditObserver.class);

    /** Actor recorded for exchanges with no security context, e.g. scheduler runs (ADR-0018). */
    public static final String SYSTEM_ACTOR = "system:supplier-exchange";

    private final ExchangeAuditRepository auditRepository;
    private final SupplierEndpointBindingRepository bindingRepository;
    private final Clock clock;
    private final PayloadCaptureLevel defaultCaptureLevel;

    public ExchangeAuditObserver(
            @NonNull ExchangeAuditRepository auditRepository,
            @NonNull SupplierEndpointBindingRepository bindingRepository,
            @NonNull Clock clock,
            @Value("${pos.supplier.audit.default-capture-level:REDACTED}") String defaultCaptureLevel) {
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository");
        this.bindingRepository = Objects.requireNonNull(bindingRepository, "bindingRepository");
        this.clock = Objects.requireNonNull(clock, "clock");
        // Defaults to REDACTED, not FULL: an unconfigured binding must not capture credentials that
        // happen to sit inside a vendor document. Fail safe, not fail open.
        this.defaultCaptureLevel = PayloadCaptureLevel.valueOf(defaultCaptureLevel);
    }

    @Override
    public void onExchange(@NonNull ExchangeContext context) {
        Objects.requireNonNull(context, "context must not be null");
        try {
            persist(context);
        } catch (RuntimeException ex) {
            // Deliberately swallowed: the exchange itself succeeded or failed on its own merits and
            // must be reported as such. Logged at ERROR because a gap in the trail needs attention.
            log.error(
                    "Failed to persist exchange audit for vendorProfileId={} capability={} correlationId={};"
                            + " the exchange result is unaffected but this row is MISSING from the audit trail",
                    context.vendorProfileId(),
                    context.capability(),
                    context.correlationId(),
                    ex);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void persist(@NonNull ExchangeContext context) {
        PayloadCaptureLevel captureLevel = resolveCaptureLevel(context.bindingId());

        ExchangeAuditEntity row = ExchangeAuditEntity.builder()
                .vendorProfileId(context.vendorProfileId())
                .supplierRef(context.supplierRef())
                .bindingId(context.bindingId())
                .capability(context.capability())
                .protocolFamily(context.protocolFamily())
                .protocolVersion(context.protocolVersion())
                .httpMethod(context.method())
                .endpointUri(truncate(context.uri(), 2048))
                .attempt(context.attempt())
                .correlationId(context.correlationId())
                .outcome(context.outcome().name())
                .httpStatus(context.httpStatus())
                .startedAt(context.startedAt())
                .durationMs(context.duration().toMillis())
                .failureDetail(truncate(context.failureDetail(), 2048))
                .captureLevel(captureLevel)
                // The load-bearing line: raw bodies are never persisted unredacted.
                .requestPayload(PayloadRedactor.applyCaptureLevel(context.requestBody(), captureLevel))
                .responsePayload(PayloadRedactor.applyCaptureLevel(context.responseBody(), captureLevel))
                .build();

        // Scheduler and client threads have no security context, so the system actor is supplied
        // explicitly and the save is flushed inside the scope -- @CreatedBy fires at flush time.
        AuditActorContext.withActor(SYSTEM_ACTOR, () -> {
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
                .filter(Objects::nonNull)
                .orElse(defaultCaptureLevel);
    }

    /** Keeps an over-long vendor URI or failure message from failing the insert on column length. */
    @Nullable
    private static String truncate(@Nullable String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max - 3) + "...";
    }

    /** Exposed for the purge job's clock, keeping time injection in one place. */
    @NonNull
    Instant now() {
        return Instant.now(clock);
    }
}

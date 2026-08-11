package com.positivity.supplier.internal.client;

import com.positivity.shared.id.UUIDv7Generator;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Thread-scoped correlation id for outbound supplier exchanges, mirroring the module's
 * {@link com.positivity.supplier.internal.config.AuditActorContext} precedent.
 *
 * <p>An inbound request's {@code X-Correlation-Id} must be reused so a vendor exchange can be
 * traced back to the operator action that caused it; a scheduler run has no inbound request, so
 * one is generated. Deliberately a thread-scoped holder rather than a request-scoped bean: the
 * per-binding scheduler is the primary caller and has no servlet scope at all, and a
 * request-scoped bean would fail there.
 *
 * <p>{@link #currentOrGenerate()} never returns empty, so the base client always stamps a
 * correlation id — an unstamped exchange is untraceable and therefore not acceptable in the
 * audit trail (ADR-0050 §7).
 */
public final class SupplierCorrelationContext {

    /** Outbound correlation header, matching the inbound header the exception handler reads. */
    public static final String CORRELATION_HEADER = "X-Correlation-Id";

    private static final ThreadLocal<String> CORRELATION_ID = new ThreadLocal<>();

    private SupplierCorrelationContext() {
        // static holder
    }

    /**
     * Runs {@code action} with the given correlation id in scope, restoring any previous value on
     * exit so nested scopes cannot leak into a caller.
     *
     * @param correlationId the id to propagate; when {@code null} or blank a fresh one is used, so
     *     a caller may pass an absent inbound header straight through
     * @param action the work to perform in scope
     */
    public static void withCorrelationId(@Nullable String correlationId, @NonNull Runnable action) {
        Objects.requireNonNull(action, "action must not be null");
        String effective = (correlationId == null || correlationId.isBlank()) ? generate() : correlationId;
        String previous = CORRELATION_ID.get();
        CORRELATION_ID.set(effective);
        try {
            action.run();
        } finally {
            if (previous == null) {
                CORRELATION_ID.remove();
            } else {
                CORRELATION_ID.set(previous);
            }
        }
    }

    /** The active correlation id, when inside a {@link #withCorrelationId} scope. */
    @NonNull
    public static Optional<String> current() {
        return Optional.ofNullable(CORRELATION_ID.get());
    }

    /**
     * The active correlation id, or a freshly generated one when there is no scope — the base
     * client's entry point, which must always have an id to stamp.
     *
     * @return a non-blank correlation id
     */
    @NonNull
    public static String currentOrGenerate() {
        String active = CORRELATION_ID.get();
        return (active == null || active.isBlank()) ? generate() : active;
    }

    @NonNull
    private static String generate() {
        return UUIDv7Generator.generate().toString();
    }
}

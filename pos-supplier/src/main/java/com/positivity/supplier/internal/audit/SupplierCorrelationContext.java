package com.positivity.supplier.internal.audit;

import com.positivity.shared.id.UUIDv7Generator;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Thread-scoped correlation id for supplier exchanges, mirroring the module's
 * {@link com.positivity.supplier.internal.audit.AuditActorContext} precedent.
 *
 * <p>Deliberately a thread-scoped holder rather than a request-scoped bean: the per-binding scheduler is
 * a primary caller and has no servlet scope at all, so a request-scoped bean would fail there.
 *
 * <p>{@link #currentOrGenerate()} never returns empty, so the base client always stamps a
 * correlation id — an unstamped exchange is untraceable and therefore not acceptable in the
 * audit trail (ADR-0050 §7).
 *
 * <h2>Who opens a scope in production</h2>
 *
 * <ol>
 *   <li>{@code SupplierCorrelationFilter} opens one for <strong>every inbound request</strong> to this
 *       module, reusing the caller's {@code X-Correlation-Id} header when present and generating an id
 *       otherwise, and echoes the effective value on the response. A vendor exchange triggered by an
 *       operator action therefore shares the operator request's correlation id, and the two join in logs
 *       and in the audit trail.
 *   <li>{@code SupplierScheduleCoordinator.runPage} opens a <strong>fresh scope per page</strong> of
 *       scheduled work, so exchanges of one page trace to one another and a reprocessed page is honestly
 *       a new correlation rather than impersonating the attempt that lost its lease.
 * </ol>
 *
 * <p>Outside those two scopes — a test, or a future caller that forgot — {@link #currentOrGenerate()}
 * still generates, so an exchange is never unstamped; it is merely uncorrelated with its cause.
 */
public final class SupplierCorrelationContext {

    /** Correlation header, inbound (read by the filter) and outbound (stamped by the base client). */
    public static final String CORRELATION_HEADER = "X-Correlation-Id";

    private static final ThreadLocal<String> CORRELATION_ID = new ThreadLocal<>();

    private SupplierCorrelationContext() {
        // static holder
    }

    /**
     * Opens a correlation scope, restoring any previous value on close so nested scopes cannot leak
     * into a caller. For callers whose work throws checked exceptions — a servlet filter chain — where
     * the {@link Runnable}-shaped {@link #withCorrelationId} cannot be used.
     *
     * @param correlationId the id to propagate; when {@code null} or blank a fresh one is used, so
     *     a caller may pass an absent inbound header straight through
     * @return the scope, exposing the effective id; must be closed, ideally by try-with-resources
     */
    @NonNull
    public static CorrelationScope open(@Nullable String correlationId) {
        String effective = (correlationId == null || correlationId.isBlank()) ? generate() : correlationId;
        String previous = CORRELATION_ID.get();
        CORRELATION_ID.set(effective);
        return new CorrelationScope(effective, previous);
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
        try (CorrelationScope scope = open(correlationId)) {
            action.run();
        }
    }

    /** The active correlation id, when inside an open scope. */
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

    /** One open correlation scope. Closing restores whatever was in scope before it opened. */
    public static final class CorrelationScope implements AutoCloseable {

        private final String correlationId;

        @Nullable
        private final String previous;

        private CorrelationScope(@NonNull String correlationId, @Nullable String previous) {
            this.correlationId = correlationId;
            this.previous = previous;
        }

        /** The effective id of this scope — the reused inbound value, or the generated one. */
        @NonNull
        public String correlationId() {
            return correlationId;
        }

        @Override
        public void close() {
            if (previous == null) {
                CORRELATION_ID.remove();
            } else {
                CORRELATION_ID.set(previous);
            }
        }
    }
}

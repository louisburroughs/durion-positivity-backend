package com.positivity.supplier.internal.client;

import com.positivity.shared.id.UUIDv7Generator;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Thread-scoped correlation id for outbound supplier exchanges, mirroring the module's
 * {@link com.positivity.supplier.internal.audit.AuditActorContext} precedent.
 *
 * <p>Deliberately a thread-scoped holder rather than a request-scoped bean: the per-binding scheduler is
 * the primary caller and has no servlet scope at all, so a request-scoped bean would fail there.
 *
 * <p>{@link #currentOrGenerate()} never returns empty, so the base client always stamps a
 * correlation id — an unstamped exchange is untraceable and therefore not acceptable in the
 * audit trail (ADR-0050 §7).
 *
 * <h2>What this class does NOT currently do</h2>
 *
 * <strong>Nothing in production establishes a scope from an inbound request.</strong>
 * {@link #withCorrelationId} exists and works, but its only caller today is a test, so every scope in
 * production is opened by {@link #currentOrGenerate()} generating a fresh id. The practical consequence:
 * a vendor exchange triggered by an operator action currently carries a correlation id unrelated to that
 * operator's request, so the two cannot be joined in logs.
 *
 * <p>Reusing an inbound {@code X-Correlation-Id} is the intended design and is worth doing — it needs a
 * {@code OncePerRequestFilter} establishing the scope for every inbound request, which is a module-wide
 * request-handling change rather than something a caller can opt into. Recorded as a CAP-317 follow-up.
 *
 * <p>Until then, do not read a correlation id from this holder on an inbound path and treat it as the
 * caller's: outside an explicit {@link #withCorrelationId} scope there is none, and
 * {@code supplier_audit_access} omits its correlation column for exactly that reason. This paragraph
 * previously claimed the inbound header "must be reused", which described an intention as though it were
 * a guarantee — the sort of javadoc the next implementer trusts and should not have to verify.
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

package com.positivity.supplier.internal.audit;

import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.NonNull;

/**
 * Thread-scoped audit-actor override for system-initiated writes (ADR-0018).
 *
 * <p>Lives in {@code internal.audit} rather than {@code internal.config} because it is not Spring
 * configuration — it is a cross-cutting context holder read by JPA auditing and written by every
 * system-initiated path. Keeping it in {@code config} made {@code service} depend on {@code config}
 * while {@code config} already depended on {@code service}, which the module's package-cycle rule
 * correctly rejected. Interactive
 * writes take their actor from the security context ({@code JpaConfig}); startup YAML
 * reconciliation runs as {@code system:yaml-bootstrap} (ADR-0050 §6) by wrapping its writes —
 * including the flush — in {@link #withActor(String, Runnable)}.
 */
public final class AuditActorContext {

    private static final ThreadLocal<String> ACTOR = new ThreadLocal<>();

    private AuditActorContext() {
        // static holder
    }

    /**
     * Runs {@code action} with the given audit actor. The action must flush any pending JPA
     * changes before returning — {@code @PreUpdate} auditing fires at flush time, and a flush
     * deferred to commit would fall back to the security-context actor.
     *
     * @param actor the audit actor to record; never blank
     * @param action the writes to perform under this actor
     */
    public static void withActor(@NonNull String actor, @NonNull Runnable action) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(action, "action must not be null");
        if (actor.isBlank()) {
            throw new IllegalArgumentException("actor must not be blank");
        }
        ACTOR.set(actor);
        try {
            action.run();
        } finally {
            ACTOR.remove();
        }
    }

    /** The active actor override, when inside a {@link #withActor(String, Runnable)} scope. */
    @NonNull
    public static Optional<String> currentActor() {
        return Optional.ofNullable(ACTOR.get());
    }
}

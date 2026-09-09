package com.positivity.tenancy;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The tenant bound to the current thread, and the only source {@link TenantAwareDataSource} reads
 * when it binds {@code app.current_tenant} on a connection checkout (ADR-0062 §2).
 *
 * <p>Nothing here decides <em>which</em> tenant a request belongs to. The gateway derives that from
 * the validated token and forwards it as {@code X-Tenant-Id}; the filter in {@code
 * pos-security-common} is what calls {@link #bind}. A caller that binds a tenant from a request
 * body, a query parameter or a client-supplied header defeats the whole model.
 *
 * <p>Absence is meaningful: with nothing bound the datasource leaves the setting unset, every
 * row-level security policy evaluates to NULL, and scoped tables read as empty and refuse inserts.
 * That is the intended fail-closed behaviour, not an error to paper over.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    /** Binds {@code tenantId} to this thread until {@link #clear()}. */
    public static void bind(@NonNull UUID tenantId) {
        CURRENT.set(tenantId);
    }

    /** The bound tenant, or empty when nothing is bound. */
    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * The bound tenant.
     *
     * @throws IllegalStateException when nothing is bound, so a code path that forgot to bind fails
     *     loudly here rather than silently reading zero rows at the database
     */
    public static @NonNull UUID require() {
        UUID tenantId = CURRENT.get();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "No tenant bound to this thread. A request path must bind TenantContext from the "
                            + "gateway's X-Tenant-Id; background work must use TenantContext.runAs.");
        }
        return tenantId;
    }

    /** Removes any binding. Always call this in a finally block. */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Runs {@code work} with {@code tenantId} bound, restoring the previous binding afterwards.
     * This is how scheduled and consumer work scopes itself to one tenant.
     */
    public static <T> T runAs(@NonNull UUID tenantId, @NonNull Supplier<T> work) {
        @Nullable UUID previous = CURRENT.get();
        CURRENT.set(tenantId);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** {@link #runAs(UUID, Supplier)} for work that returns nothing. */
    public static void runAs(@NonNull UUID tenantId, @NonNull Runnable work) {
        runAs(tenantId, () -> {
            work.run();
            return null;
        });
    }
}

package com.positivity.tenancy;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/**
 * Thread-bound tenant of the work in progress (ADR-0062 §3).
 *
 * <p>Bound once at the edge (the gateway header filter, the Kafka record interceptor, or {@link
 * TenantIterator} for scheduled work) and read by the connection binding and the Hibernate
 * resolver. Application code never binds a tenant from request data; it only reads {@link
 * #current()} or {@link #require()}. The bound id is mirrored into the {@value #MDC_KEY} MDC key so
 * every log line carries it.
 *
 * <p>Deliberately not inheritable: a child thread that needs the tenant gets it through {@link
 * TenantContextTaskDecorator} or {@link #runAs}, never implicitly.
 */
public final class TenantContext {

    /** MDC key carrying the bound tenant on every log line. */
    public static final String MDC_KEY = "tenantId";

    private static final ThreadLocal<@Nullable UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    /** The tenant bound to this thread, if any. */
    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** True when a tenant is bound to this thread. */
    public static boolean isBound() {
        return CURRENT.get() != null;
    }

    /**
     * The tenant bound to this thread.
     *
     * @throws TenantContextMissingException when nothing is bound: a code path that forgot to bind
     *     fails loudly here rather than reaching the database, where RLS would return nothing
     */
    public static UUID require() {
        UUID tenantId = CURRENT.get();
        if (tenantId == null) {
            throw new TenantContextMissingException();
        }
        return tenantId;
    }

    /**
     * Bind {@code tenantId} to this thread, replacing any previous binding. Callers pair this with
     * {@link #clear()} in a {@code finally} block; prefer {@link #runAs} or {@link #callAs}.
     */
    public static void bind(UUID tenantId) {
        CURRENT.set(tenantId);
        MDC.put(MDC_KEY, tenantId.toString());
    }

    /** Remove the binding from this thread (and the MDC key). Safe to call when nothing is bound. */
    public static void clear() {
        CURRENT.remove();
        MDC.remove(MDC_KEY);
    }

    /** Run {@code work} with {@code tenantId} bound, restoring the previous binding afterwards. */
    public static void runAs(UUID tenantId, Runnable work) {
        callAs(tenantId, () -> {
            work.run();
            return null;
        });
    }

    /** Call {@code work} with {@code tenantId} bound, restoring the previous binding afterwards. */
    public static <T> T callAs(UUID tenantId, Callable<T> work) {
        UUID previous = CURRENT.get();
        bind(tenantId);
        try {
            return work.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Tenant-bound work failed for tenant " + tenantId, e);
        } finally {
            if (previous == null) {
                clear();
            } else {
                bind(previous);
            }
        }
    }
}

package com.positivity.mcp.internal.config;

import com.positivity.tenancy.TenantContext;
import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Lets the bound tenant travel with a Reactor context (ADR-0062 plan WS6).
 *
 * <p>Registers {@link TenantContext} with Micrometer's {@link ContextRegistry} as a {@code
 * ThreadLocalAccessor}, so a captured context snapshot restores the tenant on whichever thread runs
 * the continuation. This only registers the accessor: it does not switch on Reactor's JVM-wide
 * automatic propagation, which {@code EvalTurnTracePropagation} enables on alpha for the eval turn
 * (once that hook is on, the tenant follows the same hops, which is what puts a streamed tool call's
 * audit row under the caller's tenant). Everywhere else the streaming manager re-binds the captured
 * tenant explicitly in the callbacks it owns.
 */
@Component
public class TenantContextPropagation {

    /** Context key for the bound tenant. */
    static final String TENANT_KEY = "pos.tenant";

    @PostConstruct
    void register() {
        ContextRegistry.getInstance()
                .registerThreadLocalAccessor(
                        TENANT_KEY,
                        () -> TenantContext.current().orElse(null),
                        (UUID tenantId) -> TenantContext.bind(tenantId),
                        TenantContext::clear);
    }

    @PreDestroy
    void unregister() {
        ContextRegistry.getInstance().removeThreadLocalAccessor(TENANT_KEY);
    }
}

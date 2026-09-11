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
 * automatic propagation, which {@code EvalTurnTracePropagation} enables on alpha for the eval turn.
 *
 * <p>So the accessor alone carries nothing on a normal deployment, and no code may depend on it
 * doing so. Every off-request-thread write of a tenant-scoped row re-binds the tenant itself, on
 * every profile: {@code StreamingSessionAgentManager} in the Flux callbacks it owns, and {@code
 * RequestBoundToolCallback} around each tool execution the Spring AI tool loop runs on {@code
 * boundedElastic} (that is what puts a streamed tool call's {@code mcp_tool_invocation_log} row
 * under the caller's tenant). Where the hook is on, this accessor makes the same tenant travel with
 * a captured context snapshot as well — belt and braces, never the only strap.
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

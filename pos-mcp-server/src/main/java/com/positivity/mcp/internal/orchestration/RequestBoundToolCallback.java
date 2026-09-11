package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.config.CurrentUserContext;
import com.positivity.mcp.internal.service.RequestScopedUserContext;
import com.positivity.tenancy.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Re-binds the request's context — the calling user, their bearer token and their tenant — for the
 * duration of a tool execution that may run off the request thread.
 *
 * <p>{@code ToolCallingAdvisor.adviseStream} executes tools on {@code Schedulers.boundedElastic()},
 * and {@code StreamingSessionAgentManager} clears the request-scoped {@link
 * RequestScopedUserContext} ThreadLocal in a {@code finally} on the assembly thread before the first
 * token is emitted. Anything that reads the caller at execution time — the #1422 invocation recorder
 * and the MDC correlation id — would therefore attribute every streamed tool call to {@code
 * unknown}.
 *
 * <p>The tenant is bound here for the same reason and is the sharper of the two (ADR-0062 plan
 * WS6). {@code TenantContext} is a ThreadLocal too, set by {@code TenantContextFilter} on the
 * servlet thread and never carried to {@code boundedElastic} on its own: {@link
 * com.positivity.mcp.internal.config.TenantContextPropagation} registers the accessor that would let
 * a Reactor context snapshot restore it, but the JVM-wide hook that acts on it
 * ({@code Hooks.enableAutomaticContextPropagation()}) is switched on by {@code
 * EvalTurnTracePropagation} under the {@code alpha} profile alone. So outside alpha a streamed tool
 * execution — and the {@code mcp_tool_invocation_log} insert it triggers through
 * {@code ToolInvocationRecorder} — would run unbound: under the transitional default that row lands
 * in the default tenant, under strict tenancy row-level security drops it, and {@code
 * ToolAuditService} logs the failure at WARN and returns, so nothing surfaces. Binding it on the
 * executing thread is what makes the audit row the caller's own, on every profile.
 *
 * <p>The MDC correlation id is captured and restored the same way, for the same reason: {@code
 * CorrelationIdMdcFilter} puts it in the SLF4J MDC only on the servlet thread, MDC is a ThreadLocal
 * with no automatic propagation to {@code boundedElastic} either, and {@code ToolAuditService} and
 * every other log line a tool execution emits on the worker thread would otherwise carry no
 * correlation id at all rather than merely the wrong one.
 *
 * <p>All three must be captured <strong>per request</strong>, which is why this wraps the assembled
 * callback list inside {@code chat(..)} rather than at callback-resolution time. Facade callbacks
 * are resolved once in the assistant's constructor and the agent is cached per role, so binding
 * there would stamp the first caller's identity, tenant and correlation id onto every later caller's
 * audit rows and logs — a worse failure than {@code unknown}.
 */
final class RequestBoundToolCallback implements ToolCallback {

    /**
     * MDC key the session agent managers ({@code SessionAgentManager}, {@code
     * StreamingSessionAgentManager}) and {@code CorrelationIdMdcFilter} read and write; kept in sync
     * with theirs by name rather than by reference since {@code CorrelationIdMdcFilter} is
     * package-private to {@code internal.controller}.
     */
    private static final String MDC_CORRELATION_ID_KEY = "correlationId";

    private final ToolCallback delegate;
    private final @Nullable RequestScopedUserContext userContext;
    private final @Nullable CurrentUserContext caller;
    private final @Nullable String authHeader;
    private final @Nullable UUID tenantId;
    private final @Nullable String correlationId;

    private RequestBoundToolCallback(
            ToolCallback delegate,
            @Nullable RequestScopedUserContext userContext,
            @Nullable CurrentUserContext caller,
            @Nullable String authHeader,
            @Nullable UUID tenantId,
            @Nullable String correlationId) {
        this.delegate = delegate;
        this.userContext = userContext;
        this.caller = caller;
        this.authHeader = authHeader;
        this.tenantId = tenantId;
        this.correlationId = correlationId;
    }

    /**
     * Binds each callback to the caller, tenant and correlation id currently on this thread. Returns
     * {@code callbacks} unchanged when none of the three is bound, so a context-free path keeps its
     * existing behaviour.
     */
    static @NonNull List<ToolCallback> bindCurrentRequest(
            @NonNull List<ToolCallback> callbacks, @Nullable RequestScopedUserContext userContext) {
        if (callbacks.isEmpty()) {
            return callbacks;
        }
        CurrentUserContext caller =
                userContext == null ? null : userContext.current().orElse(null);
        // Only meaningful alongside a caller: the header is relayed as that caller's credential.
        String authHeader = caller == null || userContext == null
                ? null
                : userContext.currentAuthHeader().orElse(null);
        UUID tenantId = TenantContext.current().orElse(null);
        String correlationId = MDC.get(MDC_CORRELATION_ID_KEY);
        if (caller == null && tenantId == null && correlationId == null) {
            return callbacks;
        }
        RequestScopedUserContext boundUserContext = caller == null ? null : userContext;
        return callbacks.stream()
                .map(callback -> (ToolCallback) new RequestBoundToolCallback(
                        callback, boundUserContext, caller, authHeader, tenantId, correlationId))
                .toList();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return withRequestBound(() -> delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        return withRequestBound(() -> delegate.call(toolInput, toolContext));
    }

    /**
     * Restores whatever was previously bound rather than clearing unconditionally: on the
     * non-streaming path the tool runs on the request thread, where {@code clear()} would also drop
     * the discovered-tool and write-capability signals that the session manager reads after the
     * turn. {@link TenantContext#callAs} restores the previous tenant the same way.
     */
    private String withRequestBound(Supplier<String> execution) {
        String correlation = correlationId;
        Supplier<String> correlationBound =
                correlation == null ? execution : () -> withCorrelationId(correlation, execution);
        UUID tenant = tenantId;
        Supplier<String> tenantBound =
                tenant == null ? correlationBound : () -> TenantContext.callAs(tenant, correlationBound::get);
        RequestScopedUserContext context = userContext;
        CurrentUserContext boundCaller = caller;
        if (context == null || boundCaller == null) {
            return tenantBound.get();
        }
        Optional<CurrentUserContext> previous = context.current();
        Optional<String> previousAuthHeader = context.currentAuthHeader();
        context.set(boundCaller, authHeader);
        try {
            return tenantBound.get();
        } finally {
            previous.ifPresentOrElse(
                    restored -> context.set(restored, previousAuthHeader.orElse(null)), context::clear);
        }
    }

    /**
     * Save-and-restore rather than clear, mirroring {@code CorrelationIdMdcFilter}: a pooled worker
     * thread may already be carrying its own correlation id (from a prior request bound to that
     * thread, or upstream instrumentation), and unconditionally clearing on the way out would drop
     * that rather than compose with it.
     */
    private static String withCorrelationId(String correlationId, Supplier<String> execution) {
        String previous = MDC.get(MDC_CORRELATION_ID_KEY);
        MDC.put(MDC_CORRELATION_ID_KEY, correlationId);
        try {
            return execution.get();
        } finally {
            if (previous != null) {
                MDC.put(MDC_CORRELATION_ID_KEY, previous);
            } else {
                MDC.remove(MDC_CORRELATION_ID_KEY);
            }
        }
    }
}

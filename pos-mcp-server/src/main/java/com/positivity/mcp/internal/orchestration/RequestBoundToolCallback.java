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
 * <p>Both must be captured <strong>per request</strong>, which is why this wraps the assembled
 * callback list inside {@code chat(..)} rather than at callback-resolution time. Facade callbacks
 * are resolved once in the assistant's constructor and the agent is cached per role, so binding
 * there would stamp the first caller's identity and tenant onto every later caller's audit rows — a
 * worse failure than {@code unknown}.
 */
final class RequestBoundToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final @Nullable RequestScopedUserContext userContext;
    private final @Nullable CurrentUserContext caller;
    private final @Nullable String authHeader;
    private final @Nullable UUID tenantId;

    private RequestBoundToolCallback(
            ToolCallback delegate,
            @Nullable RequestScopedUserContext userContext,
            @Nullable CurrentUserContext caller,
            @Nullable String authHeader,
            @Nullable UUID tenantId) {
        this.delegate = delegate;
        this.userContext = userContext;
        this.caller = caller;
        this.authHeader = authHeader;
        this.tenantId = tenantId;
    }

    /**
     * Binds each callback to the caller and tenant currently on this thread. Returns {@code
     * callbacks} unchanged when neither is bound, so a context-free path keeps its existing
     * behaviour.
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
        if (caller == null && tenantId == null) {
            return callbacks;
        }
        RequestScopedUserContext boundUserContext = caller == null ? null : userContext;
        return callbacks.stream()
                .map(callback -> (ToolCallback)
                        new RequestBoundToolCallback(callback, boundUserContext, caller, authHeader, tenantId))
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
        UUID tenant = tenantId;
        Supplier<String> tenantBound = tenant == null ? execution : () -> TenantContext.callAs(tenant, execution::get);
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
}

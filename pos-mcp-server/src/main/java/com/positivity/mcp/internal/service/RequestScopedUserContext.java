package com.positivity.mcp.internal.service;

import com.positivity.mcp.service.CurrentUserContext;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Request-scoped holder for the current caller's {@link CurrentUserContext} and raw
 * {@code Authorization} header (Gate 3).
 *
 * <p>The OpenAPI {@code ToolProvider} and its executors run inside the cached agent and have no
 * access to the HTTP request, so the calling thread publishes the caller (and its bearer token)
 * here before invoking the agent and clears it afterwards. Reading it per request (rather than
 * capturing at agent-build time) is what prevents a cached agent from exposing a prior, higher-
 * permission caller's tools; relaying the token is what lets a discovered op call the gateway as
 * the caller (otherwise the gateway rejects it with 401).
 *
 * <p><strong>Threading:</strong> backed by a {@link ThreadLocal}, correct for the synchronous
 * blocking path only. The streaming (Reactor) path executes on different threads; until Reactor-
 * context propagation is implemented, the provider treats an empty holder as "no discovered tools"
 * (fail-closed).
 */
@Component
public class RequestScopedUserContext {

    private record Holder(
            CurrentUserContext context, @Nullable String authHeader) {}

    private static final ThreadLocal<Holder> HOLDER = new ThreadLocal<>();
    // Names of the OpenAPI-discovered tools the provider surfaced this request, for telemetry
    // (facade vs openapi source). Written by OpenApiToolProvider inside agent.chat, read by the
    // manager when it emits the per-request telemetry event.
    private static final ThreadLocal<List<String>> DISCOVERED_OPENAPI_TOOLS = new ThreadLocal<>();

    public void set(@NonNull CurrentUserContext context) {
        set(context, null);
    }

    public void set(@NonNull CurrentUserContext context, @Nullable String authHeader) {
        HOLDER.set(new Holder(context, authHeader));
    }

    public void recordDiscoveredOpenapiTools(@NonNull List<String> toolNames) {
        DISCOVERED_OPENAPI_TOOLS.set(List.copyOf(toolNames));
    }

    public @NonNull List<String> currentDiscoveredOpenapiToolNames() {
        List<String> names = DISCOVERED_OPENAPI_TOOLS.get();
        return names == null ? List.of() : names;
    }

    public void clear() {
        HOLDER.remove();
        DISCOVERED_OPENAPI_TOOLS.remove();
    }

    public @NonNull Optional<CurrentUserContext> current() {
        Holder holder = HOLDER.get();
        return holder == null ? Optional.empty() : Optional.of(holder.context());
    }

    /** The caller's raw {@code Authorization} header, for relaying to downstream gateway calls. */
    public @NonNull Optional<String> currentAuthHeader() {
        Holder holder = HOLDER.get();
        return holder == null ? Optional.empty() : Optional.ofNullable(holder.authHeader());
    }
}

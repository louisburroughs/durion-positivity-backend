package com.positivity.mcp.internal.service;

import com.positivity.mcp.service.CurrentUserContext;
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

    public void set(@NonNull CurrentUserContext context) {
        set(context, null);
    }

    public void set(@NonNull CurrentUserContext context, @Nullable String authHeader) {
        HOLDER.set(new Holder(context, authHeader));
    }

    public void clear() {
        HOLDER.remove();
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

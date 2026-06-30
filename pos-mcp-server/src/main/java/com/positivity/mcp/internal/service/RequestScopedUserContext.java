package com.positivity.mcp.internal.service;

import com.positivity.mcp.service.CurrentUserContext;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Request-scoped holder for the current caller's {@link CurrentUserContext} (Gate 3).
 *
 * <p>The OpenAPI {@code ToolProvider} runs inside the cached agent and has no access to the HTTP
 * request, so the calling thread publishes the caller here before invoking the agent and clears it
 * afterwards. Reading it per request (rather than capturing permissions at agent-build time) is what
 * prevents a cached agent from exposing a prior, higher-permission caller's tools.
 *
 * <p><strong>Threading:</strong> backed by a {@link ThreadLocal}, so it is correct for the
 * synchronous blocking path only. The streaming (Reactor) path executes on different threads; until
 * Reactor-context propagation is implemented and verified, the provider treats an empty holder as
 * "no discovered tools" (fail-closed) rather than risk leakage.
 */
@Component
public class RequestScopedUserContext {

    private static final ThreadLocal<CurrentUserContext> HOLDER = new ThreadLocal<>();

    public void set(@NonNull CurrentUserContext context) {
        HOLDER.set(context);
    }

    public void clear() {
        HOLDER.remove();
    }

    public @NonNull Optional<CurrentUserContext> current() {
        return Optional.ofNullable(HOLDER.get());
    }
}

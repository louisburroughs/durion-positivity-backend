package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.config.ScopeGraphProperties;
import com.positivity.mcp.internal.scopegraph.ScopeSet;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetryFactory.ScopeSignal;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * ADR-0069 §9: what both session managers report about a turn's scope, in one place so the
 * synchronous and the streaming transport cannot report it differently.
 */
final class ScopeShadowSupport {

    private ScopeShadowSupport() {}

    /**
     * The telemetry view of {@code scope}: a mode, a hash, a confidence and counts. Null when the
     * turn resolved no scope, which leaves the telemetry scope fields absent.
     *
     * @param properties the rollout switches; absent in a hand-built manager, where a resolved scope
     *     can only be a shadow one because nothing is wired to enforce it
     */
    static @Nullable ScopeSignal telemetrySignal(@Nullable ScopeSet scope, @Nullable ScopeGraphProperties properties) {
        if (scope == null) {
            return null;
        }
        return new ScopeSignal(
                modeName(properties),
                scope.graphHash(),
                scope.confidence().name(),
                scope.seeds().size() + scope.reachedEntities().size(),
                scope.tools().size(),
                scope.documentIds().size(),
                // No consumer acts on the scope yet: nothing is added and retrieval is not narrowed.
                0,
                false);
    }

    private static @NonNull String modeName(@Nullable ScopeGraphProperties properties) {
        return properties == null || !properties.enabled()
                ? ScopeGraphProperties.Mode.SHADOW.name()
                : properties.mode().name();
    }
}

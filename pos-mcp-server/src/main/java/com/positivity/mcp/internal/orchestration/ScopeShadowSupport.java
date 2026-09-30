package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.config.ScopeGraphProperties;
import com.positivity.mcp.internal.config.ScopeGraphProperties.Consumer;
import com.positivity.mcp.internal.scopegraph.ScopeCardRenderer;
import com.positivity.mcp.internal.scopegraph.ScopeConsumers;
import com.positivity.mcp.internal.scopegraph.ScopeSet;
import com.positivity.mcp.internal.service.RequestScopedUserContext;
import com.positivity.mcp.internal.service.ToolInvocationRecorder;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetryFactory.ScopeSignal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * ADR-0069 §6 / §9: what both session managers do with a turn's scope and report about it, in one
 * place so the synchronous and the streaming transport cannot differ.
 */
final class ScopeShadowSupport {

    private ScopeShadowSupport() {}

    /**
     * What a turn publishes for the consumers that run inside the cached agent: the scope, the
     * facades the selection engine already added (the first of the shared slots), and the rendered
     * scope card. Built on the request thread; published where the caller is published.
     *
     * @param scope null when the turn resolved no scope
     * @param card null when no card was rendered for the turn
     */
    record ScopePublication(
            @Nullable ScopeSet scope,
            @NonNull List<String> addedTools,
            @Nullable String card) {

        static final ScopePublication NONE = new ScopePublication(null, List.of(), null);

        ScopePublication {
            addedTools = List.copyOf(addedTools);
        }

        /** Publishes next to the caller, for the same window; cleared by the same {@code clear()}. */
        void publish(@NonNull RequestScopedUserContext requestScopedUserContext) {
            if (scope != null) {
                requestScopedUserContext.recordScope(scope);
            }
            requestScopedUserContext.recordScopeAddedTools(addedTools);
            if (card != null) {
                requestScopedUserContext.recordScopeCard(card);
            }
        }

        /** The telemetry prompt layers with {@code SCOPE_CARD} appended when a card was rendered. */
        @NonNull
        List<String> withCardLayer(@NonNull List<String> layers) {
            if (card == null || layers.contains(ScopeCardRenderer.LAYER)) {
                return layers;
            }
            List<String> withCard = new ArrayList<>(layers);
            withCard.add(ScopeCardRenderer.LAYER);
            return List.copyOf(withCard);
        }
    }

    /**
     * The publication for one turn: the selection's scope and added facades, plus the card when the
     * card consumer acts (§7). Renders nothing without a switch or a scope.
     */
    static @NonNull ScopePublication publicationFor(
            ToolSelectionEngine.@NonNull ToolSelectionResult selection,
            @Nullable ScopeConsumers scopeConsumers,
            @NonNull Set<String> callerPermissionCodes) {
        if (selection.scope() == null) {
            return ScopePublication.NONE;
        }
        String card = scopeConsumers == null
                ? null
                : scopeConsumers
                        .renderCard(selection.scope(), callerPermissionCodes)
                        .orElse(null);
        return new ScopePublication(selection.scope(), selection.scopeAddedTools(), card);
    }

    /**
     * What the consumers did with the turn's scope (read from the request-scoped holder once the
     * agent has resolved its tools and retrieved, before the holder is cleared).
     *
     * @param addedTools facades then discovered operations, in the order they took the slots
     */
    record ScopeOutcome(@NonNull List<String> addedTools, boolean ragFilterApplied) {

        static final ScopeOutcome NONE = new ScopeOutcome(List.of(), false);

        ScopeOutcome {
            addedTools = List.copyOf(addedTools);
        }
    }

    /**
     * Reads the outcome, stamps it on the eval trace and counts the {@code tools} fallback: the
     * consumer was enforced on a scoped turn and neither step added anything. (The {@code rag}
     * fallback is counted by the hook itself, the {@code card} fallback at rendering.)
     */
    static @NonNull ScopeOutcome recordOutcome(
            @Nullable ScopeSet scope,
            @Nullable ScopeConsumers scopeConsumers,
            @Nullable RequestScopedUserContext requestScopedUserContext,
            @Nullable ToolInvocationRecorder toolInvocationRecorder) {
        if (scope == null || requestScopedUserContext == null) {
            return ScopeOutcome.NONE;
        }
        ScopeOutcome outcome = new ScopeOutcome(
                requestScopedUserContext.currentScopeAddedToolNames(),
                requestScopedUserContext.currentScopeRagFilterApplied());
        if (toolInvocationRecorder != null) {
            toolInvocationRecorder.recordScopeConsumers(outcome.addedTools(), outcome.ragFilterApplied());
        }
        if (scopeConsumers != null
                && scopeConsumers.enforces(Consumer.TOOLS)
                && outcome.addedTools().isEmpty()) {
            scopeConsumers.recordFallback(Consumer.TOOLS);
        }
        return outcome;
    }

    /**
     * The telemetry view of {@code scope}: a mode, a hash, a confidence, counts and what the
     * consumers did. Null when the turn resolved no scope, which leaves the telemetry scope fields
     * absent.
     *
     * @param properties the rollout switches; absent in a hand-built manager, where a resolved scope
     *     can only be a shadow one because nothing is wired to enforce it
     */
    static @Nullable ScopeSignal telemetrySignal(
            @Nullable ScopeSet scope, @Nullable ScopeGraphProperties properties, @NonNull ScopeOutcome outcome) {
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
                outcome.addedTools().size(),
                outcome.ragFilterApplied());
    }

    /** The rollout switches behind a consumer switch, or null without one. */
    static @Nullable ScopeGraphProperties propertiesOf(@Nullable ScopeConsumers scopeConsumers) {
        return scopeConsumers == null ? null : scopeConsumers.properties();
    }

    /** The card published for the current request, for the per-request prompt supplier. */
    static @NonNull Optional<String> currentCard(@Nullable RequestScopedUserContext requestScopedUserContext) {
        return requestScopedUserContext == null ? Optional.empty() : requestScopedUserContext.currentScopeCard();
    }

    private static @NonNull String modeName(@Nullable ScopeGraphProperties properties) {
        return properties == null || !properties.enabled()
                ? ScopeGraphProperties.Mode.SHADOW.name()
                : properties.mode().name();
    }
}

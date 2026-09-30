package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.config.CurrentUserContext;
import com.positivity.mcp.internal.scopegraph.ScopeSet;
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
 * <p><strong>Threading:</strong> backed by a {@link ThreadLocal}. Both the synchronous and the
 * streaming managers publish the caller on the calling thread before invoking the agent and clear it
 * in a {@code finally} on that same thread. This is correct for streaming too: the streaming assistant
 * resolves tool callbacks synchronously at Flux-assembly time (before {@code subscribe()} and before
 * any async token callback), while the holder is still set. An empty holder always fail-closes to
 * "no discovered tools".
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
    // Whether this request's resolved candidate tools include a write-capable one (a discovered op
    // with a non-GET http method). Written by OpenApiToolProvider inside agent.chat BEFORE the
    // system prompt is assembled, read by the per-request prompt supplier (WRITE-GATE layer, #1193)
    // and by the manager's telemetry emission. Unset fail-closes to false (no WRITE_GATE layer).
    private static final ThreadLocal<Boolean> WRITE_CAPABLE_TOOLS_PRESENT = new ThreadLocal<>();

    /**
     * The user's own words for this turn (#1675).
     *
     * <p>Held here rather than passed as a tool argument because the model does not copy them
     * faithfully: asked for the wording of a range it sends a normalised snippet — "in the last six
     * months" and "over the last six months" both arrive as "last six months" — and the preposition
     * it drops is the entire discriminator between a rolling window and a calendar one. A
     * model-supplied copy of the user's words is still a model output, so the only reliable source
     * is the request itself.
     */
    private static final ThreadLocal<String> USER_MESSAGE = new ThreadLocal<>();

    /**
     * This turn's resolved scope (ADR-0069 §5), published next to the caller and cleared with it.
     * Unset in mode {@code off}, on the simple-chat path and outside a chat turn; a reader treats
     * that as "no scope" and keeps today's behaviour.
     */
    private static final ThreadLocal<ScopeSet> SCOPE = new ThreadLocal<>();

    /**
     * ADR-0069 §6: what the consumers did with this turn's scope. The tools added on top of the
     * ranked cuts, in the order they were added (facades by the selection engine before the agent
     * runs, discovered operations by {@code OpenApiToolProvider} inside it; together they share one
     * cap, so the provider reads this to know how many slots are left); whether the RAG hook
     * narrowed retrieval to the scope; and the rendered scope card the per-request prompt supplier
     * appends. All cleared with the caller.
     */
    private static final ThreadLocal<List<String>> SCOPE_ADDED_TOOLS = new ThreadLocal<>();

    private static final ThreadLocal<Boolean> SCOPE_RAG_FILTER_APPLIED = new ThreadLocal<>();
    private static final ThreadLocal<String> SCOPE_CARD = new ThreadLocal<>();

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

    /** Records whether the request's resolved tools include a write-capable one (#1193). */
    public void recordWriteCapableToolsPresent(boolean present) {
        WRITE_CAPABLE_TOOLS_PRESENT.set(present);
    }

    /** True when this request's resolved tools include a write-capable one; false when unrecorded. */
    public boolean currentWriteCapableToolsPresent() {
        return Boolean.TRUE.equals(WRITE_CAPABLE_TOOLS_PRESENT.get());
    }

    /** Records this turn's raw user message so tools can read the wording the caller actually used. */
    public void recordUserMessage(@NonNull String message) {
        USER_MESSAGE.set(message);
    }

    /** This turn's raw user message, or empty when unrecorded (a non-chat entry point, or a test). */
    public @NonNull Optional<String> currentUserMessage() {
        return Optional.ofNullable(USER_MESSAGE.get());
    }

    /** Publishes this turn's scope for the consumers that run inside the cached agent (ADR-0069 §6). */
    public void recordScope(@NonNull ScopeSet scope) {
        SCOPE.set(scope);
    }

    /** This turn's scope, or empty when none was resolved for it. */
    public @NonNull Optional<ScopeSet> currentScope() {
        return Optional.ofNullable(SCOPE.get());
    }

    /** Appends the tools a consumer added on top of a ranked cut this turn (ADR-0069 §6). */
    public void recordScopeAddedTools(@NonNull List<String> toolNames) {
        if (toolNames.isEmpty()) {
            return;
        }
        List<String> added = new java.util.ArrayList<>(currentScopeAddedToolNames());
        added.addAll(toolNames);
        SCOPE_ADDED_TOOLS.set(List.copyOf(added));
    }

    /** The tools added on top of the ranked cuts so far this turn, facades first; empty when none. */
    public @NonNull List<String> currentScopeAddedToolNames() {
        List<String> names = SCOPE_ADDED_TOOLS.get();
        return names == null ? List.of() : names;
    }

    /** Records whether the RAG hook narrowed this turn's retrieval to the scope (ADR-0069 §6). */
    public void recordScopeRagFilterApplied(boolean applied) {
        SCOPE_RAG_FILTER_APPLIED.set(applied);
    }

    /** True when the RAG hook narrowed this turn's retrieval to the scope; false when unrecorded. */
    public boolean currentScopeRagFilterApplied() {
        return Boolean.TRUE.equals(SCOPE_RAG_FILTER_APPLIED.get());
    }

    /** Publishes this turn's rendered scope card for the per-request prompt supplier (ADR-0069 §7). */
    public void recordScopeCard(@NonNull String card) {
        SCOPE_CARD.set(card);
    }

    /** This turn's scope card, or empty when none was rendered for it. */
    public @NonNull Optional<String> currentScopeCard() {
        return Optional.ofNullable(SCOPE_CARD.get());
    }

    public void clear() {
        SCOPE.remove();
        SCOPE_ADDED_TOOLS.remove();
        SCOPE_RAG_FILTER_APPLIED.remove();
        SCOPE_CARD.remove();
        HOLDER.remove();
        DISCOVERED_OPENAPI_TOOLS.remove();
        WRITE_CAPABLE_TOOLS_PRESENT.remove();
        USER_MESSAGE.remove();
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

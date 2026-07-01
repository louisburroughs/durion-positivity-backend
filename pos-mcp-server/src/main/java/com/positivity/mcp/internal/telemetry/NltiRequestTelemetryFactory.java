package com.positivity.mcp.internal.telemetry;

import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry.Actor;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry.Latency;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry.Outcome;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry.PromptLayer;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry.Rag;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry.Routing;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry.Tier;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry.Tools;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Pure builder for {@link NltiRequestTelemetry} events from the chat path (Gate 1 emission).
 *
 * <p>Deterministic by design: {@code correlationId} and {@code timestamp} are supplied by the
 * caller (never read from the clock or MDC here) so the mapping is fully unit-testable. It carries
 * only the fields available synchronously at request completion — actor, prompt layers (Gate 1),
 * selected tools, latency, and outcome. Routing tier/model (Gate 4), RAG doc scores (Gate 5), and
 * write provenance (Gate 6) are left null until those gates wire their inputs through.
 */
public final class NltiRequestTelemetryFactory {

    private NltiRequestTelemetryFactory() {}

    /**
     * Builds a {@code SUCCESS}/{@code ERROR} chat telemetry event. {@code selectedToolNames} is
     * empty for the Tier-0 simple-chat path; {@code promptLayers} carries the layers composed by
     * {@code RolePromptResolver.assemble(...)} (empty when no layered prompt was assembled).
     */
    public static @NonNull NltiRequestTelemetry forChatRequest(
            @NonNull String correlationId,
            @NonNull String timestamp,
            @NonNull String primaryRole,
            int permissionCodeCount,
            @NonNull List<String> selectedToolNames,
            @NonNull List<String> promptLayers,
            boolean simpleChat,
            @Nullable String simpleChatRule,
            long totalMs,
            @NonNull String status,
            @Nullable String errorCode) {

        Actor actor = new Actor(primaryRole, permissionCodeCount);

        // Tier-0 rule path is the only tier known without the Gate 4 router; leave null otherwise.
        Routing routing = simpleChat ? new Routing(null, null, null, null, Tier.T0_RULE, simpleChatRule) : null;

        Tools tools = selectedToolNames.isEmpty()
                ? null
                : new Tools(List.copyOf(selectedToolNames), 0, selectedToolNames.size(), null);

        List<PromptLayer> layers = promptLayers.stream()
                .map(NltiRequestTelemetryFactory::toPromptLayer)
                .filter(java.util.Objects::nonNull)
                .toList();
        Rag rag = layers.isEmpty() ? null : new Rag(null, layers);

        Latency latency = new Latency(null, null, null, totalMs);
        Outcome outcome = new Outcome(status, errorCode);

        return new NltiRequestTelemetry(
                NltiRequestTelemetry.SCHEMA_VERSION,
                NltiRequestTelemetry.EVENT_TYPE,
                correlationId,
                null,
                null,
                timestamp,
                actor,
                routing,
                null,
                tools,
                rag,
                null,
                null,
                latency,
                outcome);
    }

    private static @Nullable PromptLayer toPromptLayer(@NonNull String name) {
        try {
            return PromptLayer.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknownLayer) {
            return null;
        }
    }
}

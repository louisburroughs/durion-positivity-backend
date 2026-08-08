package com.positivity.mcp.internal.telemetry;

import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry.Actor;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry.Latency;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry.Model;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry.Outcome;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry.PromptLayer;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry.Rag;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry.Routing;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry.Tier;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry.Tools;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry.Write;
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
     * The Gate 4 router decision for one request: the T1 classification signals, the selected tier,
     * and the actual model names (tier executor + router). {@code fallbackUsed} is not carried here
     * — failover ({@code mcp.model.fallback}) stays orthogonal to tier routing.
     */
    public record TierRouting(
            @Nullable String intentType,
            @Nullable String riskLevel,
            @Nullable String domain,
            @Nullable String complexity,
            @Nullable Tier tier,
            @Nullable String tierModel,
            @Nullable String routerModel) {}

    /** As the full overload, without Gate 4 tier routing or Gate 6 write signals. */
    public static @NonNull NltiRequestTelemetry forChatRequest(
            @NonNull String correlationId,
            @NonNull String timestamp,
            @NonNull String primaryRole,
            int permissionCodeCount,
            @NonNull List<String> selectedToolNames,
            @NonNull List<String> discoveredOpenapiTools,
            @NonNull List<String> promptLayers,
            boolean simpleChat,
            @Nullable String simpleChatRule,
            @Nullable String workflowState,
            long totalMs,
            @NonNull String status,
            @Nullable String errorCode) {
        return forChatRequest(
                correlationId,
                timestamp,
                primaryRole,
                permissionCodeCount,
                selectedToolNames,
                discoveredOpenapiTools,
                promptLayers,
                simpleChat,
                simpleChatRule,
                workflowState,
                totalMs,
                status,
                errorCode,
                null,
                false);
    }

    /**
     * Builds a {@code SUCCESS}/{@code ERROR} chat telemetry event. {@code selectedToolNames} is
     * empty for the Tier-0 simple-chat path; {@code promptLayers} carries the layers composed by
     * {@code RolePromptResolver.assemble(...)} (empty when no layered prompt was assembled).
     * {@code tierRouting} carries the Gate 4 router decision when the tiered router ran;
     * {@code writeCapableToolsPresent} marks a request whose candidate tools included a
     * write-capable tool (Gate 6 signal — {@code confirmationOutcome} needs the audit ledger and is
     * left null here).
     */
    public static @NonNull NltiRequestTelemetry forChatRequest(
            @NonNull String correlationId,
            @NonNull String timestamp,
            @NonNull String primaryRole,
            int permissionCodeCount,
            @NonNull List<String> selectedToolNames,
            @NonNull List<String> discoveredOpenapiTools,
            @NonNull List<String> promptLayers,
            boolean simpleChat,
            @Nullable String simpleChatRule,
            @Nullable String workflowState,
            long totalMs,
            @NonNull String status,
            @Nullable String errorCode,
            @Nullable TierRouting tierRouting,
            boolean writeCapableToolsPresent) {

        Actor actor = new Actor(primaryRole, permissionCodeCount);

        // Tier-0 rule path short-circuits before the Gate 4 router; the tool path carries the router
        // decision (when it ran) and the resolved workflow state (Gate 2C). Routing is omitted only
        // when none of the signals apply.
        Routing routing;
        if (simpleChat) {
            routing = new Routing(null, null, null, null, Tier.T0_RULE, simpleChatRule, workflowState);
        } else if (tierRouting != null) {
            routing = new Routing(
                    tierRouting.intentType(),
                    tierRouting.riskLevel(),
                    tierRouting.domain(),
                    tierRouting.complexity(),
                    tierRouting.tier(),
                    null,
                    workflowState);
        } else if (workflowState != null) {
            routing = new Routing(null, null, null, null, null, null, workflowState);
        } else {
            routing = null;
        }

        Model model = (tierRouting != null && (tierRouting.tierModel() != null || tierRouting.routerModel() != null))
                ? new Model(tierRouting.tierModel(), tierRouting.routerModel(), false)
                : null;
        Write write = writeCapableToolsPresent ? new Write(true, null, null) : null;

        Tools tools = (selectedToolNames.isEmpty() && discoveredOpenapiTools.isEmpty())
                ? null
                : new Tools(
                        List.copyOf(selectedToolNames),
                        0,
                        selectedToolNames.size(),
                        null,
                        discoveredOpenapiTools.isEmpty() ? null : List.copyOf(discoveredOpenapiTools));

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
                model,
                tools,
                rag,
                write,
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

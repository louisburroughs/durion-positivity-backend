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
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Pure builder for {@link NltiRequestTelemetry} events from the two emitting paths: the chat
 * managers ({@link #forChatRequest}, Gate 1) and the NLTI request pipeline
 * ({@link #forNltiRequest}, Gate 6).
 *
 * <p>Deterministic by design: {@code correlationId} and {@code timestamp} are supplied by the
 * caller (never read from the clock or MDC here) so the mapping is fully unit-testable. Each
 * builder carries only the fields available synchronously at request completion; fields belonging
 * to gates a given path does not run through are left null rather than filled with a placeholder.
 */
public final class NltiRequestTelemetryFactory {

    private NltiRequestTelemetryFactory() {}

    /**
     * Gate 6 write-gate signals for one NLTI request.
     *
     * @param isWrite the request was classified as a write (ACTION) intent and therefore went
     *     through the write gate, whether or not a plan was ultimately produced
     * @param confirmationOutcome terminal outcome of a write-plan confirmation —
     *     {@code confirmed}, {@code cancelled}, {@code expired}, {@code stale-data} or
     *     {@code superseded}; null on the submit/preview leg, which has no outcome yet
     * @param planArgsProvenance count of plan arguments per {@code ArgProvenance} kind (counts
     *     only — argument names and values must never enter this event)
     */
    public record WriteSignal(
            boolean isWrite,
            @Nullable String confirmationOutcome,
            @Nullable Map<String, Integer> planArgsProvenance) {}

    /**
     * The Gate 4 router decision for one request: the T1 classification signals, the selected tier,
     * and the actual model names (tier executor + router). {@code fallbackUsed} is not carried here:
     * failover ({@code mcp.model.fallback}) is orthogonal to tier routing and is read from
     * {@link FallbackUsage} when the event is built (#1691).
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
                ? new Model(tierRouting.tierModel(), tierRouting.routerModel(), FallbackUsage.consume())
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

    /**
     * Builds the telemetry event for one {@code /v1/nlt} request — the NLTI pipeline leg that the
     * chat builder above never covers ({@code POST /v1/nlt/requests} and the write-plan
     * confirm/cancel calls).
     *
     * <p>Unlike the chat path this leg knows its own {@code sessionId} and {@code requestId}, so
     * both are carried; {@code routing} carries the parsed intent type and assessed risk level,
     * and {@code write} carries the Gate 6 signals. There is no model tier, prompt-layer
     * composition, RAG retrieval, or tool selection on this path, so {@code model}, {@code rag},
     * {@code tools} and {@code quality} are omitted rather than zero-filled.
     *
     * @param intentType the {@code IntentV1} classification, when the request got far enough to be
     *     parsed
     * @param riskLevel the intent's assessed risk level, when classified
     * @param write Gate 6 write-gate signals, or null for a request that never touched the gate
     * @param totalMs wall time for the request, or null for an event that does not correspond to
     *     one call of its own (a plan superseded while another request was being served)
     */
    public static @NonNull NltiRequestTelemetry forNltiRequest(
            @NonNull String correlationId,
            @NonNull String timestamp,
            @Nullable UUID sessionId,
            @Nullable UUID requestId,
            @NonNull String primaryRole,
            int permissionCodeCount,
            @Nullable String intentType,
            @Nullable String riskLevel,
            @Nullable WriteSignal write,
            @Nullable Long totalMs,
            @NonNull String status,
            @Nullable String errorCode) {

        Routing routing = (intentType == null && riskLevel == null)
                ? null
                : new Routing(intentType, riskLevel, null, null, null, null, null);

        return new NltiRequestTelemetry(
                NltiRequestTelemetry.SCHEMA_VERSION,
                NltiRequestTelemetry.EVENT_TYPE,
                correlationId,
                sessionId == null ? null : sessionId.toString(),
                requestId == null ? null : requestId.toString(),
                timestamp,
                new Actor(primaryRole, permissionCodeCount),
                routing,
                null,
                null,
                null,
                write == null
                        ? null
                        : new Write(write.isWrite(), write.confirmationOutcome(), write.planArgsProvenance()),
                null,
                totalMs == null ? null : new Latency(null, null, null, totalMs),
                new Outcome(status, errorCode));
    }

    private static @Nullable PromptLayer toPromptLayer(@NonNull String name) {
        try {
            return PromptLayer.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknownLayer) {
            return null;
        }
    }
}

package com.positivity.mcp.internal.telemetry;

import com.positivity.mcp.internal.domain.QuestionTags;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry.Actor;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry.Latency;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry.Model;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry.Outcome;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry.PromptLayer;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry.Rag;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry.Routing;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry.Tagging;
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

    /**
     * The ADR-0069 scope resolution of one chat request (schema version 2). Counts, a hash and enum
     * names only: no entity key, tool name or {@code document_id}, and never anything the user typed.
     *
     * @param mode {@code SHADOW} or {@code ENFORCE}
     * @param graphHash the content hash of the graph snapshot the scope was resolved against
     * @param confidence {@code NONE}, {@code LOW} or {@code HIGH}
     * @param addedToolCount tools a consumer added to the request because of the scope
     * @param ragFilterApplied whether the scope narrowed this request's retrieval
     */
    public record ScopeSignal(
            @NonNull String mode,
            @NonNull String graphHash,
            @NonNull String confidence,
            int entityCount,
            int toolCount,
            int docCount,
            int addedToolCount,
            boolean ragFilterApplied) {}

    /**
     * The ADR-0068 tagging of one chat request (schema version 3), built from the turn's record.
     * Never built from {@link QuestionTags#none()}: a turn that did not tag carries no block.
     */
    public record TaggingSignal(
            @NonNull String mode,
            @Nullable String providerModel,
            @Nullable Long latencyMs,
            @Nullable String fallbackReason,
            @Nullable Double agreementRate,
            @NonNull String intent,
            @NonNull String risk,
            @NonNull String complexity,
            @NonNull String domain,
            @NonNull String workflowState,
            boolean simpleChat) {

        /** The signal of {@code tags}, or null for {@link QuestionTags#none()}. */
        public static @Nullable TaggingSignal of(@Nullable QuestionTags tags) {
            if (tags == null || tags.isNone()) {
                return null;
            }
            return new TaggingSignal(
                    tags.mode().name(),
                    tags.providerModel(),
                    tags.latencyMs(),
                    tags.fallbackReason() == null ? null : tags.fallbackReason().wireName(),
                    tags.agreementRate().isPresent() ? tags.agreementRate().getAsDouble() : null,
                    tags.intent().name(),
                    tags.risk().name(),
                    tags.complexity().name(),
                    tags.domain(),
                    tags.workflowState().name(),
                    tags.simpleChat());
        }
    }

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
                tierRouting,
                writeCapableToolsPresent,
                null);
    }

    /**
     * As above, with the request's ADR-0069 scope resolution. {@code scope} is null whenever no scope
     * was resolved (mode {@code off}, simple chat, a failed request), and the eight {@code scope*}
     * fields are then absent from the event rather than zero-filled. Pre-ADR-0068 shape: no tagging.
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
            boolean writeCapableToolsPresent,
            @Nullable ScopeSignal scope) {
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
                tierRouting,
                writeCapableToolsPresent,
                scope,
                null);
    }

    /**
     * As above, with the request's ADR-0068 tagging (schema version 3). {@code tagging} is null when
     * the turn did not tag (a failure before tagging, {@link QuestionTags#none()}); the {@code
     * tagging} block is then absent. When present, the {@link Routing} block's classification fields
     * are filled from the acting tag values unless the Gate 4 router ran, whose decision wins (§7:
     * in Wave 2 the router itself reads the tags, so the two agree).
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
            boolean writeCapableToolsPresent,
            @Nullable ScopeSignal scope,
            @Nullable TaggingSignal tagging) {

        Actor actor = new Actor(primaryRole, permissionCodeCount);

        // Tier-0 rule path short-circuits before the Gate 4 router; the tool path carries the router
        // decision (when it ran) and the resolved workflow state (Gate 2C). Since ADR-0068 the acting
        // tag values fill the classification fields whenever the turn tagged; routing is omitted
        // only when none of the signals apply.
        String intentType =
                tierRouting != null ? tierRouting.intentType() : (tagging != null ? tagging.intent() : null);
        String riskLevel = tierRouting != null ? tierRouting.riskLevel() : (tagging != null ? tagging.risk() : null);
        String domain = tierRouting != null ? tierRouting.domain() : (tagging != null ? tagging.domain() : null);
        String complexity =
                tierRouting != null ? tierRouting.complexity() : (tagging != null ? tagging.complexity() : null);
        String routedWorkflowState = workflowState != null
                ? workflowState
                : (tagging != null && !simpleChat ? tagging.workflowState() : null);
        Routing routing;
        if (simpleChat) {
            routing =
                    new Routing(intentType, riskLevel, domain, complexity, Tier.T0_RULE, simpleChatRule, workflowState);
        } else if (tierRouting != null) {
            routing = new Routing(
                    intentType, riskLevel, domain, complexity, tierRouting.tier(), null, routedWorkflowState);
        } else if (routedWorkflowState != null || tagging != null) {
            routing = new Routing(intentType, riskLevel, domain, complexity, null, null, routedWorkflowState);
        } else {
            routing = null;
        }

        // #1691: consumed on every event so a failover flag can never outlive its request on the
        // thread; a failover is reported even when no tier routing ran (tiering off, simple chat).
        boolean fallbackUsed = FallbackUsage.consume();
        boolean tierModelsKnown =
                tierRouting != null && (tierRouting.tierModel() != null || tierRouting.routerModel() != null);
        Model model = tierModelsKnown || fallbackUsed
                ? new Model(
                        tierRouting != null ? tierRouting.tierModel() : null,
                        tierRouting != null ? tierRouting.routerModel() : null,
                        fallbackUsed)
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
                outcome,
                scope == null ? null : scope.mode(),
                scope == null ? null : scope.graphHash(),
                scope == null ? null : scope.confidence(),
                scope == null ? null : scope.entityCount(),
                scope == null ? null : scope.toolCount(),
                scope == null ? null : scope.docCount(),
                scope == null ? null : scope.addedToolCount(),
                scope == null ? null : scope.ragFilterApplied(),
                tagging == null
                        ? null
                        : new Tagging(
                                tagging.mode(),
                                tagging.providerModel(),
                                tagging.latencyMs(),
                                tagging.fallbackReason(),
                                tagging.agreementRate(),
                                tagging.intent(),
                                tagging.risk(),
                                tagging.complexity(),
                                tagging.domain(),
                                tagging.workflowState(),
                                tagging.simpleChat()));
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
                new Outcome(status, errorCode),
                // This path resolves no scope (ADR-0069): the scope fields stay absent.
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                // Nor does it tag (ADR-0068).
                null);
    }

    private static @Nullable PromptLayer toPromptLayer(@NonNull String name) {
        try {
            return PromptLayer.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknownLayer) {
            return null;
        }
    }
}

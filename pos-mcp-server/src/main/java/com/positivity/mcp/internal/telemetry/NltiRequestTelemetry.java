package com.positivity.mcp.internal.telemetry;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Structured per-request telemetry event ({@code nlti.request.telemetry} v3).
 *
 * <p>This is the evaluation / observability stream defined by the NL-interface design (Gate 0).
 * It is intentionally distinct from {@code nlti_audit_event} (compliance audit) and
 * {@code mcp_tool_invocation_log} (adaptive-tuning input).
 *
 * <p>Privacy: this event carries counts, enums, ids, and scores only. Raw permission codes,
 * customer PII, VINs, and full utterances must NOT be placed in this event (see design 4.3).
 *
 * <p><strong>Coverage.</strong> Two paths emit this event and they populate different subsets, so
 * a consumer must treat an absent block as "not applicable to that path", never as a zero:
 *
 * <ul>
 *   <li><em>Chat</em> ({@code SessionAgentManager}, {@code StreamingSessionAgentManager}) —
 *       {@code routing}, {@code model}, {@code tools}, {@code rag.promptLayers}, {@code latency},
 *       {@code outcome}, and {@code write.isWrite}. It has no NLTI session or request row, so
 *       {@code sessionId} and {@code requestId} are null.
 *   <li><em>NLTI</em> ({@code NltiRequestServiceImpl}, {@code NltiWritePlanService}) —
 *       {@code sessionId}, {@code requestId}, {@code routing.intentType}, {@code routing.riskLevel},
 *       the full {@code write} block, {@code latency.totalMs}, and {@code outcome}. This path runs
 *       no model tier, prompt composition, RAG retrieval, or tool selection, so {@code model},
 *       {@code rag}, and {@code tools} are omitted.
 * </ul>
 *
 * <p>Three fields carry no real measurement from any emitter, and they fail differently — a
 * consumer must not read either kind as data:
 *
 * <ul>
 *   <li>{@link Tools#rejectedPermissionCount} is <strong>present but always literally {@code 0}</strong>
 *       on every record that carries a {@code tools} block. It is a primitive, so {@code NON_NULL}
 *       does not suppress it and it serializes as a number that looks like a count. Tool permission
 *       gating runs in SQL ({@code ToolMetadataRepository.findEnabledByPermissionsAndWorkflow}), so
 *       the rejected candidates are never materialized in Java and there is nothing to count; the
 *       {@code 0} is a placeholder, not "no tools were rejected".
 *   <li>{@link Rag#retrieved} and {@link Quality} are <strong>always null, so {@code NON_NULL} omits
 *       them from the JSON entirely.</strong> Their absence is the honest signal — unlike the field
 *       above, no value is ever presented for a consumer to misread.
 * </ul>
 *
 * <p>All three are retained rather than removed because v1 consumers already query them; see the GAP
 * notes on the Gate 7 dashboard panels.
 *
 * <p><strong>Schema version 2 (ADR-0069).</strong> Adds the eight top-level {@code scope*} fields:
 * the turn's scope-graph resolution, as a mode, a graph hash, a confidence and counts. They are
 * purely additive, all nullable, and <strong>absent</strong> unless a scope was resolved for the
 * request: never in {@code mcp.scope-graph.mode: off}, never on the simple-chat path, never on the
 * NLTI path and never on an {@code ERROR} event. Every v1 field keeps its name, type and meaning, so
 * a v1 reader that ignores unknown fields reads a v2 event unchanged.
 *
 * <p><strong>Schema version 3 (ADR-0068).</strong> Adds the nullable {@code tagging} block: the
 * turn's question tagging as a mode, the provider model, its latency, the fallback reason, the
 * agreement rate between the two taggers and the acting values of the router-derived tags. It is
 * present on every chat event that tagged (in every mode, {@code off} included, since the heuristic
 * tagger always runs) and absent on the NLTI path. The {@link Routing} block keeps its shape and is
 * now filled from the acting tag values, so {@code intentType}, {@code riskLevel}, {@code domain} and
 * {@code complexity} carry values again while the Gate 4 router stays dormant. Every v2 field is
 * unchanged.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NltiRequestTelemetry(
        int schemaVersion,
        String eventType,
        String correlationId,
        @Nullable String sessionId,
        @Nullable String requestId,
        String timestamp,
        Actor actor,
        @Nullable Routing routing,
        @Nullable Model model,
        @Nullable Tools tools,
        @Nullable Rag rag,
        @Nullable Write write,
        @Nullable Quality quality,
        @Nullable Latency latency,
        Outcome outcome,
        @Nullable String scopeMode,
        @Nullable String scopeGraphHash,
        @Nullable String scopeConfidence,
        @Nullable Integer scopeEntityCount,
        @Nullable Integer scopeToolCount,
        @Nullable Integer scopeDocCount,
        @Nullable Integer scopeAddedToolCount,
        @Nullable Boolean scopeRagFilterApplied,
        @Nullable Tagging tagging) {

    public static final int SCHEMA_VERSION = 3;
    public static final String EVENT_TYPE = "nlti.request.telemetry";

    /** Model routing tier the request was served by. */
    public enum Tier {
        T0_RULE,
        T1_ROUTER,
        T2_SIMPLE,
        T2_COMPLEX
    }

    /** Prompt layers composed for a request (Gate 1). */
    public enum PromptLayer {
        BASE,
        ROLE,
        DOMAIN,
        TOOL_USE,
        WRITE_GATE,
        /** ADR-0069 §7: the scope card, appended per request when the {@code card} consumer acts. */
        SCOPE_CARD
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Actor(String primaryRole, int permissionCodeCount) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Routing(
            @Nullable String intentType,
            @Nullable String riskLevel,
            @Nullable String domain,
            @Nullable String complexity,
            @Nullable Tier tier,
            @Nullable String simpleChatRule,
            @Nullable String workflowState) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Model(
            @Nullable String tierModel, @Nullable String routerModel, boolean fallbackUsed) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolInvocation(String toolId, boolean success, long latencyMs) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Tools(
            List<String> selected,
            int rejectedPermissionCount,
            int candidateCount,
            @Nullable List<ToolInvocation> invoked,
            @Nullable List<String> discoveredOpenapi) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RagDoc(String docId, double score) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Rag(
            @Nullable List<RagDoc> retrieved, @Nullable List<PromptLayer> promptLayers) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Write(
            boolean isWrite,
            @Nullable String confirmationOutcome,
            @Nullable Map<String, Integer> planArgsProvenance) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Quality(boolean unsupportedAnswerFlag) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Latency(
            @Nullable Long t0Ms,
            @Nullable Long t1Ms,
            @Nullable Long t2Ms,
            @Nullable Long totalMs) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Outcome(String status, @Nullable String errorCode) {}

    /**
     * ADR-0068 §6 / spec §2.8: the turn's question tagging. Enum names, labels and numbers only.
     *
     * @param mode {@code OFF}, {@code SHADOW} or {@code ENFORCE}
     * @param providerModel the decision model that answered, when the provider was called
     * @param latencyMs the provider call's wall time, when it was called
     * @param fallbackReason why the turn took the heuristic answers instead of the model's
     * @param agreementRate share of tags both taggers answered on which they agree; null unless both ran
     * @param intent the acting {@code intent}
     * @param risk the acting {@code risk}
     * @param complexity the acting {@code complexity}
     * @param domain the acting {@code domain}
     * @param workflowState the acting {@code workflow_state}
     * @param simpleChat the acting {@code simple_chat}
     * @param questionCount how many questions the provider was asked, when it was called
     * @param requestBodyBytes the size of the request body sent, when the provider was called
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Tagging(
            String mode,
            @Nullable String providerModel,
            @Nullable Long latencyMs,
            @Nullable String fallbackReason,
            @Nullable Double agreementRate,
            @Nullable String intent,
            @Nullable String risk,
            @Nullable String complexity,
            @Nullable String domain,
            @Nullable String workflowState,
            @Nullable Boolean simpleChat,
            @Nullable Integer questionCount,
            @Nullable Integer requestBodyBytes) {}
}

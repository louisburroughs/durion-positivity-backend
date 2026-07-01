package com.positivity.mcp.internal.telemetry;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Structured per-request telemetry event ({@code nlti.request.telemetry} v1).
 *
 * <p>This is the evaluation / observability stream defined by the NL-interface design (Gate 0).
 * It is intentionally distinct from {@code nlti_audit_event} (compliance audit) and
 * {@code mcp_tool_invocation_log} (adaptive-tuning input).
 *
 * <p>Privacy: this event carries counts, enums, ids, and scores only. Raw permission codes,
 * customer PII, VINs, and full utterances must NOT be placed in this event (see design 4.3).
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
        Outcome outcome) {

    public static final int SCHEMA_VERSION = 1;
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
        WRITE_GATE
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
            @Nullable List<ToolInvocation> invoked) {}

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
}

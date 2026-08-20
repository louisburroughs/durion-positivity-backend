package com.positivity.mcp.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.dto.IntentSlot;
import com.positivity.mcp.internal.dto.IntentV1;
import com.positivity.mcp.internal.dto.NltiRequestDTO;
import com.positivity.mcp.internal.dto.NltiResponseV1;
import com.positivity.mcp.internal.dto.WritePlanResponseV1;
import com.positivity.mcp.internal.entity.NltiAuditEvent;
import com.positivity.mcp.internal.entity.NltiRequest;
import com.positivity.mcp.internal.entity.NltiWritePlan;
import com.positivity.mcp.internal.enums.ArgProvenance;
import com.positivity.mcp.internal.enums.NltiAuditEventType;
import com.positivity.mcp.internal.enums.NltiRequestStatus;
import com.positivity.mcp.internal.enums.NltiRiskLevel;
import com.positivity.mcp.internal.exception.SessionOwnershipViolationException;
import com.positivity.mcp.internal.exception.WritePlanConflictException;
import com.positivity.mcp.internal.exception.WritePlanExecutionException;
import com.positivity.mcp.internal.exception.WritePlanExpiredException;
import com.positivity.mcp.internal.exception.WritePlanNotFoundException;
import com.positivity.mcp.internal.exception.WritePlanStaleException;
import com.positivity.mcp.internal.repository.NltiRequestRepository;
import com.positivity.mcp.internal.repository.NltiSessionRepository;
import com.positivity.mcp.internal.repository.NltiWritePlanRepository;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetryFactory.WriteSignal;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetryPublisher;
import com.positivity.mcp.service.AuditLedgerService;
import com.positivity.shared.id.UUIDv7Generator;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Gate 6 (#1193): the write-action confirmation gate on the NLTI-session path.
 *
 * <p>An ACTION-classified request produces a persisted {@link NltiWritePlan} preview instead of an
 * execution ({@link #previewAction}); a separate explicit {@link #confirm} executes the plan's
 * <strong>exact persisted arguments</strong> — never a re-parse of the user's text — after
 * re-checking permission, expiry, idempotency, and stale-data invariants. {@link #cancel} is the
 * caller's escape hatch. Every step lands in the append-only audit ledger
 * (PLAN → CONFIRMATION → EXECUTION_STEP → EXECUTION_COMPLETE/EXECUTION_FAILED).
 *
 * <p><strong>Transactionality:</strong> deliberately not wrapped in a single transaction — the
 * status transitions around the remote tool call must survive an execution failure (each
 * {@code save} commits on its own), so a failed execution leaves an {@code ERROR} plan rather than
 * rolling back to a confirmable one.
 */
@Service
public class NltiWritePlanService {

    private static final Logger LOGGER = LoggerFactory.getLogger(NltiWritePlanService.class);

    /** Client-context key naming the tool an ACTION request targets. */
    static final String CONTEXT_TARGET_TOOL = "targetTool";

    /** Client-context key carrying explicit, exact tool arguments. */
    static final String CONTEXT_ARGS = "args";

    /** Client-context key carrying captured source entity versions (entity ref → version token). */
    static final String CONTEXT_ENTITY_VERSIONS = "entityVersions";

    static final String STATUS_NEEDS_CLARIFICATION = "NEEDS_CLARIFICATION";

    /**
     * Terminal confirmation outcomes, mirrored out of the audit ledger onto the telemetry stream
     * ({@code write.confirmationOutcome}) and the {@link #CONFIRMATION_OUTCOME_METRIC} counter by
     * #1397 — the ledger is a database table, so before this it could back neither a dashboard
     * panel nor an alert.
     */
    static final String OUTCOME_CONFIRMED = "confirmed";

    static final String OUTCOME_CANCELLED = "cancelled";

    static final String OUTCOME_EXPIRED = "expired";

    static final String OUTCOME_STALE_DATA = "stale-data";

    static final String OUTCOME_SUPERSEDED = "superseded";

    /** Counter mirroring every confirmation outcome, tagged {@code outcome}. */
    static final String CONFIRMATION_OUTCOME_METRIC = "nlt.write_plan.confirmation.count";

    private static final TypeReference<Map<String, Object>> MAP_OF_OBJECT = new TypeReference<>() {};
    private static final TypeReference<Map<String, ArgProvenance>> MAP_OF_PROVENANCE = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> MAP_OF_STRING = new TypeReference<>() {};

    private final NltiWritePlanRepository planRepository;
    private final NltiRequestRepository requestRepository;
    private final NltiSessionRepository sessionRepository;
    private final ToolMetadataRepository toolMetadataRepository;
    private final WritePlanExecutor writePlanExecutor;
    private final SourceEntityVersionProbe versionProbe;
    private final AuditLedgerService auditLedgerService;
    private final NltiRequestTelemetryPublisher telemetryPublisher;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Value("${mcp.write-plan.ttl-minutes:10}")
    private long ttlMinutes = 10;

    public NltiWritePlanService(
            @NonNull NltiWritePlanRepository planRepository,
            @NonNull NltiRequestRepository requestRepository,
            @NonNull NltiSessionRepository sessionRepository,
            @NonNull ToolMetadataRepository toolMetadataRepository,
            @NonNull WritePlanExecutor writePlanExecutor,
            @NonNull SourceEntityVersionProbe versionProbe,
            @NonNull AuditLedgerService auditLedgerService,
            @NonNull NltiRequestTelemetryPublisher telemetryPublisher,
            @NonNull MeterRegistry meterRegistry,
            @NonNull ObjectMapper objectMapper,
            @NonNull Clock clock) {
        this.planRepository = planRepository;
        this.requestRepository = requestRepository;
        this.sessionRepository = sessionRepository;
        this.toolMetadataRepository = toolMetadataRepository;
        this.writePlanExecutor = writePlanExecutor;
        this.versionProbe = versionProbe;
        this.auditLedgerService = auditLedgerService;
        this.telemetryPublisher = telemetryPublisher;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ─── G6.2 / G6.4 / G6.5 — plan creation ──────────────────────────────────

    /**
     * Produces a persisted write-plan preview for an ACTION-classified request — the write tool is
     * <strong>not</strong> invoked. A missing target tool yields a {@code NEEDS_CLARIFICATION}
     * response (never a guessed value); a pending plan on the session whose material content
     * differs is cancelled and replaced; an identical pending plan is returned as-is.
     */
    public @NonNull NltiResponseV1 previewAction(
            @NonNull NltiRequest request,
            @NonNull NltiRequestDTO dto,
            @NonNull IntentV1 intent,
            @NonNull Set<String> callerPermissionCodes) {
        String targetTool = stringFromContext(dto.clientContext(), CONTEXT_TARGET_TOOL);
        if (targetTool == null) {
            // G6.5: a required plan input is missing — ask, never guess.
            return new NltiResponseV1(
                    request.getId(),
                    request.getCorrelationId(),
                    request.getSessionId(),
                    STATUS_NEEDS_CLARIFICATION,
                    intent,
                    Map.of("missing", CONTEXT_TARGET_TOOL));
        }

        Map<String, Object> args = new LinkedHashMap<>();
        Map<String, ArgProvenance> provenance = new LinkedHashMap<>();
        for (IntentSlot slot : intent.slots() == null ? List.<IntentSlot>of() : intent.slots()) {
            if (slot.value() != null) {
                args.put(slot.name(), slot.value());
                provenance.put(slot.name(), ArgProvenance.USER_TEXT);
            }
        }
        Map<String, Object> contextArgs = mapFromContext(dto.clientContext(), CONTEXT_ARGS);
        for (Map.Entry<String, Object> entry : contextArgs.entrySet()) {
            args.put(entry.getKey(), entry.getValue());
            provenance.put(entry.getKey(), ArgProvenance.USER_CONTEXT);
        }

        NltiRiskLevel riskLevel = riskOf(intent.riskLevel());
        // Drift guards (G6.4): every arg carries provenance; HIGH risk must not ride on inferred
        // defaults. Both hold by construction here, but the invariants are enforced regardless so
        // future plan producers cannot silently regress them.
        if (!WritePlanPolicy.allArgsHaveProvenance(args.keySet(), provenance)) {
            throw new IllegalStateException("Write-plan argument without provenance marker");
        }
        if (WritePlanPolicy.highRiskInferredRejected(riskLevel, provenance)) {
            return new NltiResponseV1(
                    request.getId(),
                    request.getCorrelationId(),
                    request.getSessionId(),
                    STATUS_NEEDS_CLARIFICATION,
                    intent,
                    Map.of("explicitSelectionRequired", WritePlanPolicy.inferredDefaultArgs(provenance)));
        }

        // Dual permission check, side one: the caller must be permitted for the target tool at
        // PLAN time (fail-closed — an unknown tool or a tool with zero grants is never plannable).
        requireToolPermission(targetTool, callerPermissionCodes);

        String argsJson = toJson(args);
        // Single-pending rule (G6.5): an identical pending plan is reused; any materially
        // different pending plan on the session is cancelled and replaced.
        List<NltiWritePlan> pending =
                planRepository.findBySessionIdAndStatus(request.getSessionId(), NltiRequestStatus.PENDING_CONFIRMATION);
        for (NltiWritePlan existing : pending) {
            if (existing.getTargetTool().equals(targetTool)
                    && existing.getArgsJson().equals(argsJson)
                    && !WritePlanPolicy.isExpired(existing.getExpiresAt(), OffsetDateTime.now(clock))) {
                return pendingResponse(request, existing);
            }
            existing.setStatus(NltiRequestStatus.CANCELLED);
            planRepository.save(existing);
            appendAudit(
                    NltiAuditEventType.CONFIRMATION,
                    request.getCorrelationId(),
                    request.getSessionId(),
                    existing.getRequestId(),
                    "outcome=superseded plan=" + existing.getId());
            // Keyed exactly like the audit entry above: this request's correlation id, the
            // superseded plan's own request. It carries no latency — it is a side effect of the
            // request being served, not a call of its own.
            recordConfirmationOutcome(existing, request.getCorrelationId(), OUTCOME_SUPERSEDED, null);
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        NltiWritePlan plan = new NltiWritePlan();
        plan.setId(UUIDv7Generator.generate());
        plan.setRequestId(request.getId());
        plan.setSessionId(request.getSessionId());
        plan.setIdempotencyKey(UUIDv7Generator.generate().toString());
        plan.setTargetTool(targetTool);
        plan.setArgsJson(argsJson);
        plan.setArgProvenanceJson(toJson(provenance));
        plan.setRiskLevel(riskLevel);
        Map<String, String> entityVersions = stringMapFromContext(dto.clientContext(), CONTEXT_ENTITY_VERSIONS);
        plan.setSourceEntityVersionsJson(entityVersions.isEmpty() ? null : toJson(entityVersions));
        plan.setSummaryText(buildSummary(targetTool, args, provenance, riskLevel));
        plan.setStatus(NltiRequestStatus.PENDING_CONFIRMATION);
        plan.setExpiresAt(now.plusMinutes(ttlMinutes));
        planRepository.save(plan);

        appendAudit(
                NltiAuditEventType.PLAN,
                request.getCorrelationId(),
                request.getSessionId(),
                request.getId(),
                "plan=" + plan.getId() + " tool=" + targetTool + " risk=" + riskLevel);

        request.setStatus(NltiRequestStatus.PENDING_CONFIRMATION);
        requestRepository.save(request);

        LOGGER.info(
                "NLTI write plan created plan={} request={} tool={} risk={} expiresAt={}",
                plan.getId(),
                request.getId(),
                targetTool,
                riskLevel,
                plan.getExpiresAt());
        return pendingResponse(request, plan);
    }

    // ─── G6.3 / G6.6 / G6.7 — confirmation + exact execution ─────────────────

    /**
     * Confirms and executes the write plan attached to {@code requestId}, using the exact persisted
     * arguments. Re-checks caller permission (dual check), expiry, idempotency (a completed plan's
     * outcome is returned without re-execution), and stale source data (changed entity versions
     * cancel the plan and force a re-preview).
     */
    public @NonNull WritePlanResponseV1 confirm(
            @NonNull UUID requestId,
            @NonNull String subjectId,
            @NonNull Set<String> callerPermissionCodes,
            @Nullable String idempotencyKey,
            @Nullable String authHeader) {
        long startedAtNanos = System.nanoTime();
        NltiWritePlan plan = loadOwnedPlan(requestId, subjectId);
        NltiRequest request = requestRepository
                .findById(requestId)
                .orElseThrow(() -> new WritePlanNotFoundException("No NLTI request found: " + requestId));

        if (idempotencyKey != null && !idempotencyKey.equals(plan.getIdempotencyKey())) {
            throw new WritePlanConflictException("Idempotency key does not match the plan for request " + requestId);
        }

        switch (plan.getStatus()) {
            case COMPLETE -> {
                // G6.6: an executed idempotency key is terminal — return the original outcome.
                return toResponse(plan);
            }
            case CANCELLED -> throw new WritePlanConflictException("Write plan was cancelled; submit a new request");
            case EXPIRED -> throw new WritePlanExpiredException("Write plan expired; submit a new request");
            case EXECUTING -> throw new WritePlanConflictException("Write plan execution is already in progress");
            case ERROR ->
                throw new WritePlanConflictException("Write plan execution previously failed; submit a new request");
            case PENDING_CONFIRMATION, CONFIRMED, ACCEPTED -> {
                // proceed
            }
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        if (WritePlanPolicy.isExpired(plan.getExpiresAt(), now)) {
            transition(plan, request, NltiRequestStatus.EXPIRED);
            appendPlanAudit(plan, request, NltiAuditEventType.CONFIRMATION, "outcome=expired");
            recordConfirmationOutcome(plan, request.getCorrelationId(), OUTCOME_EXPIRED, elapsedMs(startedAtNanos));
            throw new WritePlanExpiredException(
                    "Write plan expired at " + plan.getExpiresAt() + "; re-preview required");
        }

        // Dual permission check, side two: the caller must STILL hold the permission at execution
        // time — a grant revoked after plan time must block execution.
        requireToolPermission(plan.getTargetTool(), callerPermissionCodes);

        // G6.7: stale-data protection for risk >= MEDIUM when versions were captured at plan time.
        if (plan.getRiskLevel() != NltiRiskLevel.LOW && plan.getSourceEntityVersionsJson() != null) {
            Map<String, String> captured = fromJson(plan.getSourceEntityVersionsJson(), MAP_OF_STRING);
            Map<String, String> current = versionProbe.currentVersions(plan.getTargetTool(), captured);
            if (!captured.equals(current)) {
                transition(plan, request, NltiRequestStatus.CANCELLED);
                appendPlanAudit(plan, request, NltiAuditEventType.CONFIRMATION, "outcome=stale-data");
                recordConfirmationOutcome(
                        plan, request.getCorrelationId(), OUTCOME_STALE_DATA, elapsedMs(startedAtNanos));
                throw new WritePlanStaleException(
                        "Source data changed since the plan was previewed; re-preview required");
            }
        }

        transition(plan, request, NltiRequestStatus.CONFIRMED);
        appendPlanAudit(plan, request, NltiAuditEventType.CONFIRMATION, "outcome=confirmed");
        // Emitted at the confirmation decision, not after execution: the outcome being reported is
        // "the caller confirmed and the gate let it through". Whether the target tool then
        // succeeded is the EXECUTION_COMPLETE/EXECUTION_FAILED audit pair's business, and is
        // already visible as the endpoint's HTTP status.
        recordConfirmationOutcome(plan, request.getCorrelationId(), OUTCOME_CONFIRMED, elapsedMs(startedAtNanos));

        transition(plan, request, NltiRequestStatus.EXECUTING);
        // Destructive audit events propagate write failures — a non-auditable execution never runs.
        appendPlanAudit(plan, request, NltiAuditEventType.EXECUTION_STEP, "tool=" + plan.getTargetTool());

        try {
            // The exact persisted args — never a re-parse of the user's text.
            String result = writePlanExecutor.execute(plan.getTargetTool(), plan.getArgsJson(), authHeader);
            plan.setExecutedAt(OffsetDateTime.now(clock));
            plan.setExecutionResult(result);
            transition(plan, request, NltiRequestStatus.COMPLETE);
            appendPlanAudit(plan, request, NltiAuditEventType.EXECUTION_COMPLETE, "tool=" + plan.getTargetTool());
            return toResponse(plan);
        } catch (RuntimeException executionFailure) {
            transition(plan, request, NltiRequestStatus.ERROR);
            appendPlanAudit(
                    plan,
                    request,
                    NltiAuditEventType.EXECUTION_FAILED,
                    "tool=" + plan.getTargetTool() + " error="
                            + executionFailure.getClass().getSimpleName());
            if (executionFailure instanceof WritePlanExecutionException) {
                throw executionFailure;
            }
            throw new WritePlanExecutionException("Write-plan execution failed", executionFailure);
        }
    }

    /** Cancels a pending plan (idempotent for already-cancelled/expired plans). */
    public @NonNull WritePlanResponseV1 cancel(@NonNull UUID requestId, @NonNull String subjectId) {
        long startedAtNanos = System.nanoTime();
        NltiWritePlan plan = loadOwnedPlan(requestId, subjectId);
        NltiRequest request = requestRepository
                .findById(requestId)
                .orElseThrow(() -> new WritePlanNotFoundException("No NLTI request found: " + requestId));
        switch (plan.getStatus()) {
            case CANCELLED, EXPIRED -> {
                return toResponse(plan);
            }
            case COMPLETE, EXECUTING, ERROR ->
                throw new WritePlanConflictException(
                        "Write plan is " + plan.getStatus() + " and can no longer be cancelled");
            case PENDING_CONFIRMATION, CONFIRMED, ACCEPTED -> {
                transition(plan, request, NltiRequestStatus.CANCELLED);
                appendPlanAudit(plan, request, NltiAuditEventType.CONFIRMATION, "outcome=cancelled");
                // Only the branch that actually cancels reports an outcome — the idempotent replay
                // above already reported one on the call that first cancelled the plan, and
                // counting it again would inflate the cancellation rate.
                recordConfirmationOutcome(
                        plan, request.getCorrelationId(), OUTCOME_CANCELLED, elapsedMs(startedAtNanos));
                return toResponse(plan);
            }
        }
        throw new IllegalStateException("Unhandled write plan status: " + plan.getStatus());
    }

    // ─── internals ───────────────────────────────────────────────────────────

    private @NonNull NltiWritePlan loadOwnedPlan(@NonNull UUID requestId, @NonNull String subjectId) {
        NltiWritePlan plan = planRepository
                .findByRequestId(requestId)
                .orElseThrow(() -> new WritePlanNotFoundException("No write plan for request: " + requestId));
        if (sessionRepository
                .findByIdAndSubjectId(plan.getSessionId(), subjectId)
                .isEmpty()) {
            throw new SessionOwnershipViolationException(
                    "Write plan session is not owned by the authenticated subject: " + plan.getSessionId());
        }
        return plan;
    }

    /**
     * Fail-closed tool permission gate (both plan time and execution time): the target tool must be
     * a known discovered tool with at least one permission grant the caller holds (OR semantics,
     * matching candidate selection). Unknown tools and grantless tools are never executable.
     */
    private void requireToolPermission(@NonNull String targetTool, @NonNull Set<String> callerPermissionCodes) {
        Optional<UUID> toolId = toolMetadataRepository.findDiscoveredToolIdByName(targetTool);
        if (toolId.isEmpty()) {
            throw new AccessDeniedException("Unknown or non-executable target tool: " + targetTool);
        }
        List<String> required = toolMetadataRepository.listToolPermissions(toolId.get());
        boolean permitted = required.stream().anyMatch(callerPermissionCodes::contains);
        if (!permitted) {
            throw new AccessDeniedException("Caller lacks the permission required for tool: " + targetTool);
        }
    }

    private void transition(
            @NonNull NltiWritePlan plan, @NonNull NltiRequest request, @NonNull NltiRequestStatus status) {
        plan.setStatus(status);
        planRepository.save(plan);
        request.setStatus(status);
        requestRepository.save(request);
    }

    private void appendPlanAudit(
            @NonNull NltiWritePlan plan,
            @NonNull NltiRequest request,
            @NonNull NltiAuditEventType type,
            @NonNull String payloadRef) {
        appendAudit(
                type,
                request.getCorrelationId(),
                plan.getSessionId(),
                plan.getRequestId(),
                "plan=" + plan.getId() + " " + payloadRef);
    }

    /**
     * Mirrors one terminal confirmation outcome out of the audit ledger (#1397): a tagged counter
     * for alerting and an {@code nlti.request.telemetry} event carrying
     * {@code write.confirmationOutcome} for the Gate 7 panels. Both are observability side effects
     * of a decision that is already persisted, so neither may fail the call — the publisher
     * swallows its own failures and the counter cannot throw.
     *
     * @param totalMs wall time of the confirm/cancel call, or null when the outcome was produced
     *     as a side effect of serving some other request (a superseded plan)
     */
    private void recordConfirmationOutcome(
            @NonNull NltiWritePlan plan, @NonNull UUID correlationId, @NonNull String outcome, @Nullable Long totalMs) {
        meterRegistry.counter(CONFIRMATION_OUTCOME_METRIC, "outcome", outcome).increment();
        telemetryPublisher.emit(
                correlationId,
                plan.getSessionId(),
                plan.getRequestId(),
                // The intent was classified on the submit leg and is joined by correlation id;
                // risk travels with the plan, so it is repeated here.
                null,
                plan.getRiskLevel().name(),
                new WriteSignal(true, outcome, provenanceCounts(plan)),
                totalMs,
                NltiRequestServiceImpl.TELEMETRY_STATUS_SUCCESS,
                null);
    }

    /**
     * Counts the plan's arguments by provenance kind for {@code write.planArgsProvenance}. Counts
     * only — argument names and values must never reach the telemetry stream (see the privacy note
     * on {@code NltiRequestTelemetry}). Returns null rather than an empty map for an argument-less
     * plan, and never throws: unreadable provenance JSON must not break a confirmation.
     */
    private @Nullable Map<String, Integer> provenanceCounts(@NonNull NltiWritePlan plan) {
        Map<ArgProvenance, Integer> counts = new EnumMap<>(ArgProvenance.class);
        try {
            for (ArgProvenance provenance :
                    fromJson(plan.getArgProvenanceJson(), MAP_OF_PROVENANCE).values()) {
                counts.merge(provenance, 1, Integer::sum);
            }
        } catch (RuntimeException unreadableProvenance) {
            LOGGER.warn("Write-plan provenance unreadable for telemetry plan={}", plan.getId(), unreadableProvenance);
            return null;
        }
        if (counts.isEmpty()) {
            return null;
        }
        Map<String, Integer> byName = new TreeMap<>();
        counts.forEach((provenance, count) -> byName.put(provenance.name(), count));
        return Map.copyOf(byName);
    }

    private static long elapsedMs(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }

    private void appendAudit(
            @NonNull NltiAuditEventType type,
            @NonNull UUID correlationId,
            @NonNull UUID sessionId,
            @NonNull UUID requestId,
            @NonNull String payloadRef) {
        NltiAuditEvent event = new NltiAuditEvent();
        event.setId(UUIDv7Generator.generate());
        event.setCorrelationId(correlationId);
        event.setSessionId(sessionId);
        event.setRequestId(requestId);
        event.setEventType(type);
        event.setTimestamp(OffsetDateTime.now(clock));
        event.setPayloadRef(payloadRef);
        auditLedgerService.append(event);
    }

    private @NonNull NltiResponseV1 pendingResponse(@NonNull NltiRequest request, @NonNull NltiWritePlan plan) {
        return new NltiResponseV1(
                request.getId(),
                request.getCorrelationId(),
                request.getSessionId(),
                NltiRequestStatus.PENDING_CONFIRMATION.name(),
                toResponse(plan),
                null);
    }

    private @NonNull WritePlanResponseV1 toResponse(@NonNull NltiWritePlan plan) {
        Map<String, Object> args = fromJson(plan.getArgsJson(), MAP_OF_OBJECT);
        Map<String, ArgProvenance> provenance = fromJson(plan.getArgProvenanceJson(), MAP_OF_PROVENANCE);
        return new WritePlanResponseV1(
                plan.getId(),
                plan.getRequestId(),
                plan.getSessionId(),
                plan.getStatus().name(),
                plan.getTargetTool(),
                args,
                WritePlanPolicy.inferredDefaultArgs(provenance),
                plan.getRiskLevel().name(),
                plan.getSummaryText(),
                plan.getIdempotencyKey(),
                plan.getExpiresAt(),
                plan.getExecutedAt(),
                plan.getExecutionResult());
    }

    private @NonNull String buildSummary(
            @NonNull String targetTool,
            @NonNull Map<String, Object> args,
            @NonNull Map<String, ArgProvenance> provenance,
            @NonNull NltiRiskLevel riskLevel) {
        StringBuilder summary = new StringBuilder("Will call ")
                .append(targetTool)
                .append(" (risk ")
                .append(riskLevel)
                .append(") with ");
        if (args.isEmpty()) {
            summary.append("no arguments");
        } else {
            summary.append(args.size()).append(" argument(s): ");
            summary.append(String.join(
                    ", ",
                    args.entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .toList()));
        }
        Set<String> inferred = WritePlanPolicy.inferredDefaultArgs(provenance);
        if (!inferred.isEmpty()) {
            summary.append(". Inferred defaults (please review): ").append(String.join(", ", inferred));
        }
        summary.append(". Confirm to execute.");
        return summary.toString();
    }

    private static @NonNull NltiRiskLevel riskOf(@Nullable String riskLevel) {
        if (riskLevel == null || riskLevel.isBlank()) {
            return NltiRiskLevel.LOW;
        }
        try {
            return NltiRiskLevel.valueOf(riskLevel.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NltiRiskLevel.LOW;
        }
    }

    private static @Nullable String stringFromContext(@Nullable Map<String, Object> context, @NonNull String key) {
        if (context == null) {
            return null;
        }
        Object value = context.get(key);
        return (value instanceof String s && !s.isBlank()) ? s : null;
    }

    private static @NonNull Map<String, Object> mapFromContext(
            @Nullable Map<String, Object> context, @NonNull String key) {
        if (context == null) {
            return Map.of();
        }
        Object value = context.get(key);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String name) {
                    result.put(name, entry.getValue());
                }
            }
            return result;
        }
        return Map.of();
    }

    private static @NonNull Map<String, String> stringMapFromContext(
            @Nullable Map<String, Object> context, @NonNull String key) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : mapFromContext(context, key).entrySet()) {
            if (entry.getValue() != null) {
                result.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        return result;
    }

    private @NonNull String toJson(@NonNull Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize write-plan payload", e);
        }
    }

    private <T> @NonNull T fromJson(@NonNull String json, @NonNull TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize persisted write-plan payload", e);
        }
    }
}

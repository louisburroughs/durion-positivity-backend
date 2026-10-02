package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.dto.AuditEventAppend;
import com.positivity.mcp.internal.dto.IntentSlot;
import com.positivity.mcp.internal.dto.IntentV1;
import com.positivity.mcp.internal.dto.NltiRequestDTO;
import com.positivity.mcp.internal.dto.NltiResponseV1;
import com.positivity.mcp.internal.dto.WritePlanResponseV1;
import com.positivity.mcp.internal.entity.NltiRequest;
import com.positivity.mcp.internal.entity.NltiSession;
import com.positivity.mcp.internal.entity.NltiWritePlan;
import com.positivity.mcp.internal.enums.NltiAuditEventType;
import com.positivity.mcp.internal.enums.NltiRequestStatus;
import com.positivity.mcp.internal.enums.NltiRiskLevel;
import com.positivity.mcp.internal.exception.SessionNotFoundException;
import com.positivity.mcp.internal.exception.SessionOwnershipViolationException;
import com.positivity.mcp.internal.exception.WritePlanConflictException;
import com.positivity.mcp.internal.exception.WritePlanExecutionException;
import com.positivity.mcp.internal.exception.WritePlanExpiredException;
import com.positivity.mcp.internal.exception.WritePlanStaleException;
import com.positivity.mcp.internal.repository.NltiRequestRepository;
import com.positivity.mcp.internal.repository.NltiSessionRepository;
import com.positivity.mcp.internal.repository.NltiWritePlanRepository;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry;
import com.positivity.mcp.tenancy.BoundTenant;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/**
 * Gate 6 (#1193) service invariants: plan-not-execute previews, single pending plan per session,
 * expiry, idempotent re-confirm, dual permission check, stale-data cancellation, and exact
 * persisted-args execution.
 */
@ExtendWith(MockitoExtension.class)
@ExtendWith(BoundTenant.class)
class NltiWritePlanServiceTest {

    // Hardcoded test UUIDs — no UUID.randomUUID() per ADR
    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-7000-8000-000000000101");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-7000-8000-000000000102");
    private static final UUID CORRELATION_ID = UUID.fromString("00000000-0000-7000-8000-000000000103");
    private static final UUID TOOL_ID = UUID.fromString("00000000-0000-7000-8000-000000000104");
    private static final UUID INTENT_ID = UUID.fromString("00000000-0000-7000-8000-000000000105");
    private static final UUID PLAN_ID = UUID.fromString("00000000-0000-7000-8000-000000000106");

    private static final String SUBJECT = "alice";
    private static final String TOOL = "purchaseOrderCreate";
    private static final String PERMISSION = "order:po:create";
    private static final Set<String> CALLER_PERMS = Set.of(PERMISSION, "AUTHENTICATED");
    private static final Instant NOW = Instant.parse("2026-08-07T12:00:00Z");

    @Mock
    private NltiWritePlanRepository planRepository;

    @Mock
    private NltiRequestRepository requestRepository;

    @Mock
    private NltiSessionRepository sessionRepository;

    @Mock
    private ToolMetadataRepository toolMetadataRepository;

    @Mock
    private WritePlanExecutor writePlanExecutor;

    @Mock
    private SourceEntityVersionProbe versionProbe;

    @Mock
    private AuditLedgerService auditLedgerService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final RecordingTelemetry telemetry = new RecordingTelemetry();

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private NltiWritePlanService service;

    @BeforeEach
    void setUp() {
        service = new NltiWritePlanService(
                planRepository,
                requestRepository,
                new NltiSessionAccess(sessionRepository),
                toolMetadataRepository,
                writePlanExecutor,
                versionProbe,
                new AccountingEventWriteGuard(writePlanExecutor, objectMapper),
                auditLedgerService,
                telemetry.publisher(),
                meterRegistry,
                objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static NltiRequest request() {
        NltiRequest request = new NltiRequest();
        request.setId(REQUEST_ID);
        request.setCorrelationId(CORRELATION_ID);
        request.setSessionId(SESSION_ID);
        request.setStatus(NltiRequestStatus.ACCEPTED);
        request.setPromptHash("hash");
        return request;
    }

    private static IntentV1 actionIntent(String riskLevel, List<IntentSlot> slots) {
        return IntentV1Factory.action(riskLevel, slots);
    }

    /** Small helper keeping the record construction in one place. */
    private static final class IntentV1Factory {
        private IntentV1Factory() {}

        static IntentV1 action(String riskLevel, List<IntentSlot> slots) {
            return new IntentV1(INTENT_ID, "ACTION", "READY", riskLevel, slots, List.of());
        }
    }

    private NltiWritePlan pendingPlan() {
        NltiWritePlan plan = new NltiWritePlan();
        plan.setId(PLAN_ID);
        plan.setRequestId(REQUEST_ID);
        plan.setSessionId(SESSION_ID);
        plan.setIdempotencyKey("idem-key-1");
        plan.setTargetTool(TOOL);
        plan.setArgsJson("{\"poNumber\":\"PO-77\"}");
        plan.setArgProvenanceJson("{\"poNumber\":\"USER_TEXT\"}");
        plan.setRiskLevel(NltiRiskLevel.MEDIUM);
        plan.setSummaryText("summary");
        plan.setStatus(NltiRequestStatus.PENDING_CONFIRMATION);
        plan.setExpiresAt(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusMinutes(5));
        return plan;
    }

    private void stubOwnership() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(ownedSession(SUBJECT)));
    }

    private static NltiSession ownedSession(String subjectId) {
        NltiSession session = new NltiSession();
        session.setId(SESSION_ID);
        session.setSubjectId(subjectId);
        return session;
    }

    private void stubPermission() {
        lenient().when(toolMetadataRepository.findDiscoveredToolIdByName(TOOL)).thenReturn(Optional.of(TOOL_ID));
        lenient().when(toolMetadataRepository.listToolPermissions(TOOL_ID)).thenReturn(List.of(PERMISSION));
    }

    // ─── plan creation ────────────────────────────────────────────────────────

    @Test
    @DisplayName("previewAction persists a PENDING_CONFIRMATION plan and does NOT execute")
    void previewAction_persistsPlanWithoutExecuting() {
        stubPermission();
        when(planRepository.findBySessionIdAndStatus(SESSION_ID, NltiRequestStatus.PENDING_CONFIRMATION))
                .thenReturn(List.of());
        NltiRequest request = request();
        NltiRequestDTO dto = new NltiRequestDTO(
                "create purchase order PO-77",
                SESSION_ID,
                Map.of("targetTool", TOOL, "args", Map.of("poNumber", "PO-77")));

        NltiResponseV1 response =
                service.previewAction(request, dto, actionIntent("MEDIUM", List.of()), CALLER_PERMS, null);

        assertThat(response.status()).isEqualTo("PENDING_CONFIRMATION");
        ArgumentCaptor<NltiWritePlan> planCaptor = ArgumentCaptor.forClass(NltiWritePlan.class);
        verify(planRepository).save(planCaptor.capture());
        NltiWritePlan saved = planCaptor.getValue();
        assertThat(saved.getTargetTool()).isEqualTo(TOOL);
        assertThat(saved.getArgsJson()).contains("PO-77");
        assertThat(saved.getStatus()).isEqualTo(NltiRequestStatus.PENDING_CONFIRMATION);
        assertThat(saved.getExpiresAt())
                .isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusMinutes(10));
        assertThat(saved.getIdempotencyKey()).isNotBlank();
        assertThat(request.getStatus()).isEqualTo(NltiRequestStatus.PENDING_CONFIRMATION);
        verify(writePlanExecutor, never()).execute(anyString(), anyString(), any());
        ArgumentCaptor<AuditEventAppend> auditCaptor = ArgumentCaptor.forClass(AuditEventAppend.class);
        verify(auditLedgerService).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().eventType()).isEqualTo(NltiAuditEventType.PLAN);
    }

    @Test
    @DisplayName("previewAction without a target tool returns NEEDS_CLARIFICATION and stores no plan")
    void previewAction_missingTargetTool_needsClarification() {
        NltiResponseV1 response = service.previewAction(
                request(),
                new NltiRequestDTO("delete everything", SESSION_ID, null),
                actionIntent("HIGH", List.of()),
                CALLER_PERMS,
                null);

        assertThat(response.status()).isEqualTo("NEEDS_CLARIFICATION");
        verify(planRepository, never()).save(any());
        verify(writePlanExecutor, never()).execute(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("previewAction cancels a materially different pending plan and replaces it (single-pending)")
    void previewAction_supersedesDifferentPendingPlan() {
        stubPermission();
        NltiWritePlan existing = pendingPlan();
        existing.setArgsJson("{\"poNumber\":\"PO-OLD\"}");
        when(planRepository.findBySessionIdAndStatus(SESSION_ID, NltiRequestStatus.PENDING_CONFIRMATION))
                .thenReturn(List.of(existing));
        NltiRequestDTO dto = new NltiRequestDTO(
                "actually make it PO-77", SESSION_ID, Map.of("targetTool", TOOL, "args", Map.of("poNumber", "PO-77")));

        NltiResponseV1 response =
                service.previewAction(request(), dto, actionIntent("MEDIUM", List.of()), CALLER_PERMS, null);

        assertThat(existing.getStatus()).isEqualTo(NltiRequestStatus.CANCELLED);
        assertThat(response.status()).isEqualTo("PENDING_CONFIRMATION");
        // saved twice: the cancelled predecessor and the replacement plan
        verify(planRepository, org.mockito.Mockito.times(2)).save(any(NltiWritePlan.class));
    }

    @Test
    @DisplayName("previewAction reuses an identical pending plan instead of churning it")
    void previewAction_reusesIdenticalPendingPlan() {
        stubPermission();
        NltiWritePlan existing = pendingPlan();
        existing.setArgsJson(toJson(Map.of("poNumber", "PO-77")));
        when(planRepository.findBySessionIdAndStatus(SESSION_ID, NltiRequestStatus.PENDING_CONFIRMATION))
                .thenReturn(List.of(existing));
        NltiRequestDTO dto = new NltiRequestDTO(
                "create purchase order PO-77",
                SESSION_ID,
                Map.of("targetTool", TOOL, "args", Map.of("poNumber", "PO-77")));

        NltiResponseV1 response =
                service.previewAction(request(), dto, actionIntent("MEDIUM", List.of()), CALLER_PERMS, null);

        assertThat(response.status()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(((WritePlanResponseV1) response.result()).planId()).isEqualTo(PLAN_ID);
        verify(planRepository, never()).save(any());
    }

    @Test
    @DisplayName("previewAction is permission-gated at plan time (fail-closed)")
    void previewAction_withoutPermission_denied() {
        when(toolMetadataRepository.findDiscoveredToolIdByName(TOOL)).thenReturn(Optional.of(TOOL_ID));
        when(toolMetadataRepository.listToolPermissions(TOOL_ID)).thenReturn(List.of("other:perm:code"));
        NltiRequestDTO dto = new NltiRequestDTO("create po", SESSION_ID, Map.of("targetTool", TOOL));

        assertThatThrownBy(() ->
                        service.previewAction(request(), dto, actionIntent("MEDIUM", List.of()), CALLER_PERMS, null))
                .isInstanceOf(AccessDeniedException.class);
        verify(planRepository, never()).save(any());
    }

    // ─── confirmation ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("confirm executes the EXACT persisted args and completes the plan")
    void confirm_executesExactPersistedArgs() {
        stubOwnership();
        stubPermission();
        NltiWritePlan plan = pendingPlan();
        when(planRepository.findByRequestId(REQUEST_ID)).thenReturn(Optional.of(plan));
        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request()));
        when(writePlanExecutor.execute(TOOL, plan.getArgsJson(), "Bearer tok")).thenReturn("created PO-77");

        WritePlanResponseV1 outcome = service.confirm(REQUEST_ID, SUBJECT, CALLER_PERMS, "idem-key-1", "Bearer tok");

        // The exact persisted argsJson string — never re-parsed user text.
        verify(writePlanExecutor).execute(TOOL, "{\"poNumber\":\"PO-77\"}", "Bearer tok");
        assertThat(outcome.status()).isEqualTo("COMPLETE");
        assertThat(outcome.executionResult()).isEqualTo("created PO-77");
        assertThat(plan.getExecutedAt()).isNotNull();
        ArgumentCaptor<AuditEventAppend> auditCaptor = ArgumentCaptor.forClass(AuditEventAppend.class);
        verify(auditLedgerService, org.mockito.Mockito.times(3)).append(auditCaptor.capture());
        assertThat(auditCaptor.getAllValues())
                .extracting(AuditEventAppend::eventType)
                .containsExactly(
                        NltiAuditEventType.CONFIRMATION,
                        NltiAuditEventType.EXECUTION_STEP,
                        NltiAuditEventType.EXECUTION_COMPLETE);
    }

    @Test
    @DisplayName("confirm on an expired plan marks it EXPIRED and never executes")
    void confirm_expiredPlan_rejectedWithoutExecution() {
        stubOwnership();
        NltiWritePlan plan = pendingPlan();
        plan.setExpiresAt(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusSeconds(1));
        when(planRepository.findByRequestId(REQUEST_ID)).thenReturn(Optional.of(plan));
        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request()));

        assertThatThrownBy(() -> service.confirm(REQUEST_ID, SUBJECT, CALLER_PERMS, null, null))
                .isInstanceOf(WritePlanExpiredException.class);

        assertThat(plan.getStatus()).isEqualTo(NltiRequestStatus.EXPIRED);
        verify(writePlanExecutor, never()).execute(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("re-confirm of an executed plan returns the prior outcome without re-executing (idempotency)")
    void confirm_completedPlan_returnsPriorOutcome() {
        stubOwnership();
        NltiWritePlan plan = pendingPlan();
        plan.setStatus(NltiRequestStatus.COMPLETE);
        plan.setExecutionResult("created PO-77");
        plan.setExecutedAt(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusMinutes(1));
        when(planRepository.findByRequestId(REQUEST_ID)).thenReturn(Optional.of(plan));
        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request()));

        WritePlanResponseV1 outcome = service.confirm(REQUEST_ID, SUBJECT, CALLER_PERMS, "idem-key-1", null);

        assertThat(outcome.status()).isEqualTo("COMPLETE");
        assertThat(outcome.executionResult()).isEqualTo("created PO-77");
        verify(writePlanExecutor, never()).execute(anyString(), anyString(), any());
        verify(auditLedgerService, never()).append(any());
    }

    @Test
    @DisplayName("confirm with a mismatched idempotency key is rejected")
    void confirm_mismatchedIdempotencyKey_conflict() {
        stubOwnership();
        when(planRepository.findByRequestId(REQUEST_ID)).thenReturn(Optional.of(pendingPlan()));
        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request()));

        assertThatThrownBy(() -> service.confirm(REQUEST_ID, SUBJECT, CALLER_PERMS, "wrong-key", null))
                .isInstanceOf(WritePlanConflictException.class);
        verify(writePlanExecutor, never()).execute(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("confirm re-checks permission at execution time: revoked grant blocks execution")
    void confirm_permissionRevokedSincePlan_denied() {
        stubOwnership();
        when(planRepository.findByRequestId(REQUEST_ID)).thenReturn(Optional.of(pendingPlan()));
        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request()));
        when(toolMetadataRepository.findDiscoveredToolIdByName(TOOL)).thenReturn(Optional.of(TOOL_ID));
        when(toolMetadataRepository.listToolPermissions(TOOL_ID)).thenReturn(List.of(PERMISSION));

        assertThatThrownBy(() -> service.confirm(REQUEST_ID, SUBJECT, Set.of("AUTHENTICATED"), null, null))
                .isInstanceOf(AccessDeniedException.class);
        verify(writePlanExecutor, never()).execute(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("confirm with changed source entity versions cancels the plan and forces re-preview")
    void confirm_staleSourceData_cancelsAndRejects() {
        stubOwnership();
        stubPermission();
        NltiWritePlan plan = pendingPlan();
        plan.setSourceEntityVersionsJson("{\"po/PO-77\":\"v1\"}");
        when(planRepository.findByRequestId(REQUEST_ID)).thenReturn(Optional.of(plan));
        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request()));
        when(versionProbe.currentVersions(TOOL, Map.of("po/PO-77", "v1"))).thenReturn(Map.of("po/PO-77", "v2"));

        assertThatThrownBy(() -> service.confirm(REQUEST_ID, SUBJECT, CALLER_PERMS, null, null))
                .isInstanceOf(WritePlanStaleException.class);

        assertThat(plan.getStatus()).isEqualTo(NltiRequestStatus.CANCELLED);
        verify(writePlanExecutor, never()).execute(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("execution failure marks the plan ERROR and audits EXECUTION_FAILED")
    void confirm_executionFailure_marksErrorAndAudits() {
        stubOwnership();
        stubPermission();
        NltiWritePlan plan = pendingPlan();
        when(planRepository.findByRequestId(REQUEST_ID)).thenReturn(Optional.of(plan));
        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request()));
        when(writePlanExecutor.execute(anyString(), anyString(), any()))
                .thenThrow(new WritePlanExecutionException("downstream 422"));

        assertThatThrownBy(() -> service.confirm(REQUEST_ID, SUBJECT, CALLER_PERMS, null, null))
                .isInstanceOf(WritePlanExecutionException.class);

        assertThat(plan.getStatus()).isEqualTo(NltiRequestStatus.ERROR);
        ArgumentCaptor<AuditEventAppend> auditCaptor = ArgumentCaptor.forClass(AuditEventAppend.class);
        verify(auditLedgerService, org.mockito.Mockito.times(3)).append(auditCaptor.capture());
        assertThat(auditCaptor.getAllValues())
                .extracting(AuditEventAppend::eventType)
                .containsExactly(
                        NltiAuditEventType.CONFIRMATION,
                        NltiAuditEventType.EXECUTION_STEP,
                        NltiAuditEventType.EXECUTION_FAILED);
    }

    @Test
    @DisplayName("confirm on a foreign session is an ownership violation")
    void confirm_foreignSession_ownershipViolation() {
        when(planRepository.findByRequestId(REQUEST_ID)).thenReturn(Optional.of(pendingPlan()));
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(ownedSession(SUBJECT)));

        assertThatThrownBy(() -> service.confirm(REQUEST_ID, "mallory", CALLER_PERMS, null, null))
                .isInstanceOf(SessionOwnershipViolationException.class);
        verify(writePlanExecutor, never()).execute(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("confirm on a session the bound tenant does not have is not-found (ADR-0062 WS6)")
    void confirm_sessionAbsentInTenant_notFound() {
        when(planRepository.findByRequestId(REQUEST_ID)).thenReturn(Optional.of(pendingPlan()));
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm(REQUEST_ID, SUBJECT, CALLER_PERMS, null, null))
                .isInstanceOf(SessionNotFoundException.class);
        verify(writePlanExecutor, never()).execute(anyString(), anyString(), any());
    }

    // ─── cancel ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("cancel moves a pending plan to CANCELLED; repeated cancel is idempotent")
    void cancel_pendingThenIdempotent() {
        stubOwnership();
        NltiWritePlan plan = pendingPlan();
        when(planRepository.findByRequestId(REQUEST_ID)).thenReturn(Optional.of(plan));
        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request()));

        WritePlanResponseV1 first = service.cancel(REQUEST_ID, SUBJECT);
        assertThat(first.status()).isEqualTo("CANCELLED");
        assertThat(plan.getStatus()).isEqualTo(NltiRequestStatus.CANCELLED);

        WritePlanResponseV1 second = service.cancel(REQUEST_ID, SUBJECT);
        assertThat(second.status()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("cancel of an executed plan is rejected")
    void cancel_completedPlan_conflict() {
        stubOwnership();
        NltiWritePlan plan = pendingPlan();
        plan.setStatus(NltiRequestStatus.COMPLETE);
        when(planRepository.findByRequestId(REQUEST_ID)).thenReturn(Optional.of(plan));
        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request()));

        assertThatThrownBy(() -> service.cancel(REQUEST_ID, SUBJECT)).isInstanceOf(WritePlanConflictException.class);
    }

    // ─── #1397: confirmation outcomes mirrored out of the audit ledger ───────

    /**
     * Before #1397 these five outcomes existed only as rows in {@code nlti_audit_event} — a
     * database table with no log or metric mirror, so the Gate 7 confirmation panel ran on HTTP
     * status codes and the alert on a proxy. Each outcome must now reach both the telemetry stream
     * and the tagged counter.
     */
    @Test
    @DisplayName("confirm → telemetry and counter both record outcome=confirmed")
    void confirm_recordsConfirmedOutcome() {
        stubOwnership();
        NltiWritePlan plan = pendingPlan();
        when(planRepository.findByRequestId(REQUEST_ID)).thenReturn(Optional.of(plan));
        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request()));
        when(toolMetadataRepository.findDiscoveredToolIdByName(TOOL)).thenReturn(Optional.of(TOOL_ID));
        when(toolMetadataRepository.listToolPermissions(TOOL_ID)).thenReturn(List.of(PERMISSION));
        when(writePlanExecutor.execute(anyString(), anyString(), any())).thenReturn("{\"ok\":true}");

        service.confirm(REQUEST_ID, SUBJECT, CALLER_PERMS, null, null);

        assertThat(outcomeCount(NltiWritePlanService.OUTCOME_CONFIRMED)).isEqualTo(1.0);
        NltiRequestTelemetry event = telemetry.only();
        assertThat(event.correlationId()).isEqualTo(CORRELATION_ID.toString());
        assertThat(event.write().isWrite()).isTrue();
        assertThat(event.write().confirmationOutcome()).isEqualTo(NltiWritePlanService.OUTCOME_CONFIRMED);
        assertThat(event.routing().riskLevel()).isEqualTo(NltiRiskLevel.MEDIUM.name());
        assertThat(event.sessionId()).isEqualTo(SESSION_ID.toString());
        assertThat(event.requestId()).isEqualTo(REQUEST_ID.toString());
        // Counts by provenance kind only — the plan's one USER_TEXT arg, never its name or value.
        assertThat(event.write().planArgsProvenance()).containsExactlyInAnyOrderEntriesOf(Map.of("USER_TEXT", 1));
        assertThat(event.write().planArgsProvenance().toString()).doesNotContain("poNumber", "PO-77");
    }

    @Test
    @DisplayName("cancel → telemetry and counter both record outcome=cancelled, once per real cancellation")
    void cancel_recordsCancelledOutcomeOnlyOnTheCancellingCall() {
        stubOwnership();
        NltiWritePlan plan = pendingPlan();
        when(planRepository.findByRequestId(REQUEST_ID)).thenReturn(Optional.of(plan));
        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request()));

        service.cancel(REQUEST_ID, SUBJECT);
        service.cancel(REQUEST_ID, SUBJECT);

        // The idempotent replay must not inflate the cancellation rate.
        assertThat(outcomeCount(NltiWritePlanService.OUTCOME_CANCELLED)).isEqualTo(1.0);
        assertThat(telemetry.withConfirmationOutcome()).hasSize(1);
        assertThat(telemetry.only().write().confirmationOutcome()).isEqualTo(NltiWritePlanService.OUTCOME_CANCELLED);
    }

    @Test
    @DisplayName("confirm of an expired plan → outcome=expired")
    void confirm_expiredPlan_recordsExpiredOutcome() {
        stubOwnership();
        NltiWritePlan plan = pendingPlan();
        plan.setExpiresAt(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusMinutes(1));
        when(planRepository.findByRequestId(REQUEST_ID)).thenReturn(Optional.of(plan));
        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request()));

        assertThatThrownBy(() -> service.confirm(REQUEST_ID, SUBJECT, CALLER_PERMS, null, null))
                .isInstanceOf(WritePlanExpiredException.class);

        assertThat(outcomeCount(NltiWritePlanService.OUTCOME_EXPIRED)).isEqualTo(1.0);
        assertThat(telemetry.only().write().confirmationOutcome()).isEqualTo(NltiWritePlanService.OUTCOME_EXPIRED);
    }

    @Test
    @DisplayName("confirm against changed source data → outcome=stale-data")
    void confirm_staleSourceData_recordsStaleDataOutcome() {
        stubOwnership();
        NltiWritePlan plan = pendingPlan();
        plan.setSourceEntityVersionsJson("{\"po:1\":\"v1\"}");
        when(planRepository.findByRequestId(REQUEST_ID)).thenReturn(Optional.of(plan));
        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request()));
        when(toolMetadataRepository.findDiscoveredToolIdByName(TOOL)).thenReturn(Optional.of(TOOL_ID));
        when(toolMetadataRepository.listToolPermissions(TOOL_ID)).thenReturn(List.of(PERMISSION));
        when(versionProbe.currentVersions(anyString(), any())).thenReturn(Map.of("po:1", "v2"));

        assertThatThrownBy(() -> service.confirm(REQUEST_ID, SUBJECT, CALLER_PERMS, null, null))
                .isInstanceOf(WritePlanStaleException.class);

        assertThat(outcomeCount(NltiWritePlanService.OUTCOME_STALE_DATA)).isEqualTo(1.0);
        assertThat(telemetry.only().write().confirmationOutcome()).isEqualTo(NltiWritePlanService.OUTCOME_STALE_DATA);
    }

    @Test
    @DisplayName("a pending plan displaced by a materially different preview → outcome=superseded")
    void previewAction_supersedingPendingPlan_recordsSupersededOutcome() {
        NltiWritePlan existing = pendingPlan();
        existing.setArgsJson("{\"poNumber\":\"PO-OLD\"}");
        when(planRepository.findBySessionIdAndStatus(SESSION_ID, NltiRequestStatus.PENDING_CONFIRMATION))
                .thenReturn(List.of(existing));
        when(toolMetadataRepository.findDiscoveredToolIdByName(TOOL)).thenReturn(Optional.of(TOOL_ID));
        when(toolMetadataRepository.listToolPermissions(TOOL_ID)).thenReturn(List.of(PERMISSION));

        service.previewAction(
                request(),
                new NltiRequestDTO("create a purchase order", SESSION_ID, Map.of("targetTool", TOOL)),
                actionIntent("MEDIUM", List.of(new IntentSlot("poNumber", "PO-NEW", 1.0))),
                CALLER_PERMS,
                null);

        assertThat(outcomeCount(NltiWritePlanService.OUTCOME_SUPERSEDED)).isEqualTo(1.0);
        NltiRequestTelemetry event = telemetry.only();
        assertThat(event.write().confirmationOutcome()).isEqualTo(NltiWritePlanService.OUTCOME_SUPERSEDED);
        // Not a call of its own — it happened while another request was being served.
        assertThat(event.latency()).isNull();
    }

    // ─── #2374: guarded accounting event writes ──────────────────────────────

    private static final String RETRY_TOOL = AccountingEventWriteGuard.RETRY_TOOL;
    private static final String RETRY_PERMISSION = "accounting:events:retry";
    private static final String EVENT_ID = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b";
    private static final String RETRY_ARGS_JSON = "{\"pathParams\":{\"eventId\":\"" + EVENT_ID + "\"}}";
    private static final String AUTH = "Bearer tok";

    private void stubRetryPermission() {
        when(toolMetadataRepository.findDiscoveredToolIdByName(RETRY_TOOL)).thenReturn(Optional.of(TOOL_ID));
        when(toolMetadataRepository.listToolPermissions(TOOL_ID)).thenReturn(List.of(RETRY_PERMISSION));
    }

    private void eventReads(String status) {
        when(writePlanExecutor.execute(AccountingEventWriteGuard.EVENT_READ_TOOL, RETRY_ARGS_JSON, AUTH))
                .thenReturn("{\"eventId\":\"" + EVENT_ID + "\",\"eventType\":\"INVOICE_FINALIZED\","
                        + "\"sourceSystem\":\"POS\",\"status\":\"" + status + "\","
                        + "\"transactionDate\":\"2026-08-13T10:15:00\",\"payload\":{\"totalAmount\":150.00}}");
    }

    /** The rules dry run: a published rule version posting the event's two lines. */
    private void rulesResolve(String versionNumber) {
        when(writePlanExecutor.execute(eq(AccountingEventWriteGuard.RESOLVE_TEST_TOOL), anyString(), eq(AUTH)))
                .thenReturn("{\"matched\":true,\"matchedRule\":{\"ruleSetName\":\"Invoice posting\",\"versionNumber\":"
                        + versionNumber + ",\"ruleVersionId\":\"018f0a1b-0000-7000-8000-0000000000aa\"},"
                        + "\"resolvedLines\":[{\"accountCode\":\"1100\",\"debitAmount\":150.00},"
                        + "{\"accountCode\":\"4000\",\"creditAmount\":150.00}]}");
    }

    /** What the guard pins for the event and rules as currently stubbed. */
    private String currentPin() {
        return new AccountingEventWriteGuard(writePlanExecutor, objectMapper)
                .inspect(RETRY_TOOL, Map.of("pathParams", Map.of("eventId", EVENT_ID)), AUTH)
                .fingerprint();
    }

    private static NltiRequestDTO retryRequest() {
        return new NltiRequestDTO(
                "retry accounting event " + EVENT_ID,
                SESSION_ID,
                Map.of("targetTool", RETRY_TOOL, "args", Map.of("pathParams", Map.of("eventId", EVENT_ID))));
    }

    private NltiWritePlan guardedPlan(String pin) {
        NltiWritePlan plan = pendingPlan();
        plan.setTargetTool(RETRY_TOOL);
        plan.setArgsJson(RETRY_ARGS_JSON);
        plan.setArgProvenanceJson("{\"pathParams\":\"USER_CONTEXT\"}");
        plan.setRiskLevel(NltiRiskLevel.HIGH);
        plan.setSourceEntityVersionsJson(toJson(Map.of(AccountingEventWriteGuard.PIN_KEY, pin)));
        return plan;
    }

    @Test
    @DisplayName("#2374: a retry of a FAILED event previews as a HIGH plan that says what posts, and runs nothing")
    void previewAction_guardedRetryOfFailedEvent_highRiskPlanWithPostingPreview() {
        stubRetryPermission();
        eventReads("FAILED");
        rulesResolve("3");
        when(planRepository.findBySessionIdAndStatus(SESSION_ID, NltiRequestStatus.PENDING_CONFIRMATION))
                .thenReturn(List.of());

        NltiResponseV1 response = service.previewAction(
                request(), retryRequest(), actionIntent("LOW", List.of()), Set.of(RETRY_PERMISSION), AUTH);

        assertThat(response.status()).isEqualTo("PENDING_CONFIRMATION");
        ArgumentCaptor<NltiWritePlan> planCaptor = ArgumentCaptor.forClass(NltiWritePlan.class);
        verify(planRepository).save(planCaptor.capture());
        NltiWritePlan saved = planCaptor.getValue();
        // The classifier said LOW; a posting to accounting is HIGH whatever it said.
        assertThat(saved.getRiskLevel()).isEqualTo(NltiRiskLevel.HIGH);
        assertThat(saved.getArgsJson()).isEqualTo(RETRY_ARGS_JSON);
        assertThat(saved.getSummaryText())
                .contains("risk HIGH", EVENT_ID, "INVOICE_FINALIZED", "totalAmount=150.00", "is FAILED")
                .contains("posting rule set Invoice posting version 3", "DR 1100 150.00, CR 4000 150.00")
                .contains("A journal entry will post", "there is no undo", "reversing entry")
                .endsWith("Confirm to execute.");
        // The preview is pinned with the plan, so confirm can tell whether it still holds.
        assertThat(saved.getSourceEntityVersionsJson())
                .isEqualTo(toJson(Map.of(AccountingEventWriteGuard.PIN_KEY, currentPin())));
        // Only the reads ran; the retry itself waits for confirmation.
        verify(writePlanExecutor, org.mockito.Mockito.atLeastOnce())
                .execute(AccountingEventWriteGuard.EVENT_READ_TOOL, RETRY_ARGS_JSON, AUTH);
        verify(writePlanExecutor, never()).execute(eq(RETRY_TOOL), anyString(), any());
    }

    @Test
    @DisplayName("#2374: arguments carrying their own Authorization header are not previewed, and nothing is read")
    void previewAction_guardedRetryWithOwnAuthorization_preconditionFailedWithoutRead() {
        stubRetryPermission();
        NltiRequestDTO dto = new NltiRequestDTO(
                "retry accounting event " + EVENT_ID,
                SESSION_ID,
                Map.of(
                        "targetTool",
                        RETRY_TOOL,
                        "args",
                        Map.of(
                                "pathParams",
                                Map.of("eventId", EVENT_ID),
                                "headers",
                                Map.of("Authorization", "Bearer someone-else"))));

        NltiResponseV1 response =
                service.previewAction(request(), dto, actionIntent("HIGH", List.of()), Set.of(RETRY_PERMISSION), AUTH);

        assertThat(response.status()).isEqualTo("NEEDS_CLARIFICATION");
        assertThat(response.meta())
                .extractingByKey(NltiWritePlanService.META_PRECONDITION_FAILED)
                .asString()
                .contains("own Authorization header");
        verify(planRepository, never()).save(any());
        org.mockito.Mockito.verifyNoInteractions(writePlanExecutor);
    }

    @Test
    @DisplayName("#2374: an identical pending plan whose pinned preview no longer holds is replaced, not reused")
    void previewAction_guardedIdenticalPlanWithStalePin_superseded() {
        stubRetryPermission();
        eventReads("FAILED");
        rulesResolve("4");
        NltiWritePlan existing = guardedPlan("pinned-under-version-3");
        when(planRepository.findBySessionIdAndStatus(SESSION_ID, NltiRequestStatus.PENDING_CONFIRMATION))
                .thenReturn(List.of(existing));

        NltiResponseV1 response = service.previewAction(
                request(), retryRequest(), actionIntent("HIGH", List.of()), Set.of(RETRY_PERMISSION), AUTH);

        assertThat(response.status()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(existing.getStatus()).isEqualTo(NltiRequestStatus.CANCELLED);
        verify(planRepository, org.mockito.Mockito.times(2)).save(any(NltiWritePlan.class));
    }

    @Test
    @DisplayName("#2374: a retry of a PROCESSED event is not previewed: NEEDS_CLARIFICATION, no plan")
    void previewAction_guardedRetryOfProcessedEvent_preconditionFailedWithoutPlan() {
        stubRetryPermission();
        eventReads("PROCESSED");

        NltiResponseV1 response = service.previewAction(
                request(), retryRequest(), actionIntent("HIGH", List.of()), Set.of(RETRY_PERMISSION), AUTH);

        assertThat(response.status()).isEqualTo("NEEDS_CLARIFICATION");
        assertThat(response.meta())
                .extractingByKey(NltiWritePlanService.META_PRECONDITION_FAILED)
                .asString()
                .contains(EVENT_ID, "already posted", "reversing entry");
        verify(planRepository, never()).save(any());
        verify(auditLedgerService, never()).append(any());
        verify(writePlanExecutor, never()).execute(eq(RETRY_TOOL), anyString(), any());
    }

    @Test
    @DisplayName("#2374: a caller without the retry permission is denied before the event is read")
    void previewAction_guardedRetryWithoutPermission_deniedBeforeAnyRead() {
        stubRetryPermission();

        assertThatThrownBy(() -> service.previewAction(
                        request(), retryRequest(), actionIntent("HIGH", List.of()), CALLER_PERMS, AUTH))
                .isInstanceOf(AccessDeniedException.class);

        org.mockito.Mockito.verifyNoInteractions(writePlanExecutor);
    }

    @Test
    @DisplayName("#2374: confirm re-reads the status, then executes the exact persisted args")
    void confirm_guardedRetryStillFailed_readsThenExecutes() {
        stubOwnership();
        stubRetryPermission();
        eventReads("FAILED");
        rulesResolve("3");
        NltiWritePlan plan = guardedPlan(currentPin());
        when(planRepository.findByRequestId(REQUEST_ID)).thenReturn(Optional.of(plan));
        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request()));
        when(writePlanExecutor.execute(RETRY_TOOL, RETRY_ARGS_JSON, AUTH)).thenReturn("{\"status\":\"RECEIVED\"}");

        WritePlanResponseV1 outcome = service.confirm(REQUEST_ID, SUBJECT, Set.of(RETRY_PERMISSION), null, AUTH);

        assertThat(outcome.status()).isEqualTo("COMPLETE");
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(writePlanExecutor);
        order.verify(writePlanExecutor).execute(AccountingEventWriteGuard.EVENT_READ_TOOL, RETRY_ARGS_JSON, AUTH);
        order.verify(writePlanExecutor).execute(RETRY_TOOL, RETRY_ARGS_JSON, AUTH);
    }

    @Test
    @DisplayName("#2374: a status that moved on since the preview cancels the plan as stale data and runs nothing")
    void confirm_guardedRetryStatusMovedOn_cancelledAsStaleWithoutExecution() {
        stubOwnership();
        stubRetryPermission();
        NltiWritePlan plan = guardedPlan("pinned-at-preview");
        when(planRepository.findByRequestId(REQUEST_ID)).thenReturn(Optional.of(plan));
        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request()));
        // An earlier attempt whose outcome the caller never saw has landed in the meantime.
        eventReads("RECEIVED");

        assertThatThrownBy(() -> service.confirm(REQUEST_ID, SUBJECT, Set.of(RETRY_PERMISSION), null, AUTH))
                .isInstanceOf(WritePlanStaleException.class)
                .hasMessageContaining("earlier attempt may still be running");

        assertThat(plan.getStatus()).isEqualTo(NltiRequestStatus.CANCELLED);
        verify(writePlanExecutor, never()).execute(eq(RETRY_TOOL), anyString(), any());
        assertThat(outcomeCount(NltiWritePlanService.OUTCOME_STALE_DATA)).isEqualTo(1.0);
        ArgumentCaptor<AuditEventAppend> auditCaptor = ArgumentCaptor.forClass(AuditEventAppend.class);
        verify(auditLedgerService).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().payloadRef()).contains("outcome=stale-data");
    }

    @Test
    @DisplayName("#2374: rules that changed since the preview cancel the plan as stale data and run nothing")
    void confirm_guardedRetryRulesChanged_cancelledAsStaleWithoutExecution() {
        stubOwnership();
        stubRetryPermission();
        eventReads("FAILED");
        rulesResolve("3");
        NltiWritePlan plan = guardedPlan(currentPin());
        when(planRepository.findByRequestId(REQUEST_ID)).thenReturn(Optional.of(plan));
        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request()));
        // A new rule version was published between the preview and the confirmation.
        rulesResolve("4");

        assertThatThrownBy(() -> service.confirm(REQUEST_ID, SUBJECT, Set.of(RETRY_PERMISSION), null, AUTH))
                .isInstanceOf(WritePlanStaleException.class)
                .hasMessageContaining("what the preview showed has changed")
                .hasMessageContaining("version 4");

        assertThat(plan.getStatus()).isEqualTo(NltiRequestStatus.CANCELLED);
        verify(writePlanExecutor, never()).execute(eq(RETRY_TOOL), anyString(), any());
        assertThat(outcomeCount(NltiWritePlanService.OUTCOME_STALE_DATA)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("#2374: the generic version probe sees the client's entity versions, never the guard's pin")
    void confirm_guardedPlanWithEntityVersions_probeNeverSeesThePin() {
        stubOwnership();
        stubRetryPermission();
        eventReads("FAILED");
        rulesResolve("3");
        NltiWritePlan plan = guardedPlan(currentPin());
        plan.setSourceEntityVersionsJson(toJson(Map.of(
                AccountingEventWriteGuard.PIN_KEY,
                currentPin(),
                "invoice:018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d",
                "v7")));
        when(planRepository.findByRequestId(REQUEST_ID)).thenReturn(Optional.of(plan));
        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request()));
        // Strict stubbing: a probe call carrying the pin would not match and would fail the test.
        when(versionProbe.currentVersions(RETRY_TOOL, Map.of("invoice:018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d", "v7")))
                .thenReturn(Map.of("invoice:018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d", "v7"));
        when(writePlanExecutor.execute(RETRY_TOOL, RETRY_ARGS_JSON, AUTH)).thenReturn("{\"status\":\"RECEIVED\"}");

        WritePlanResponseV1 outcome = service.confirm(REQUEST_ID, SUBJECT, Set.of(RETRY_PERMISSION), null, AUTH);

        assertThat(outcome.status()).isEqualTo("COMPLETE");
    }

    private double outcomeCount(String outcome) {
        return meterRegistry
                .counter(NltiWritePlanService.CONFIRMATION_OUTCOME_METRIC, "outcome", outcome)
                .count();
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

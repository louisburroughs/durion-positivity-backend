package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.positivity.mcp.internal.config.CurrentUserContext;
import com.positivity.mcp.internal.config.ScopeGraphProperties;
import com.positivity.mcp.internal.domain.EvalTurnTrace;
import com.positivity.mcp.internal.domain.ScopeTrace;
import com.positivity.mcp.internal.repository.EvalTurnTraceRepository;
import com.positivity.mcp.internal.scopegraph.Access;
import com.positivity.mcp.internal.scopegraph.MatchKind;
import com.positivity.mcp.internal.scopegraph.NodeAttributes;
import com.positivity.mcp.internal.scopegraph.ScopeMetrics;
import com.positivity.mcp.internal.scopegraph.ScopeSet;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AlphaEvalTurnTraceRecorderTest {

    private static final Instant NOW = Instant.parse("2026-09-03T20:00:00Z");
    private static final Duration RETENTION = Duration.ofHours(24);
    private static final CurrentUserContext USER = new CurrentUserContext(
            "diana.rowe",
            UUID.fromString("01960010-0000-7000-8000-000000000002"),
            "LOCATION_MANAGER",
            Set.of("LOCATION_MANAGER"),
            Set.of(),
            Set.of("accounting:report:view"));

    private final EvalTurnTraceRepository repository = mock(EvalTurnTraceRepository.class);
    private final AlphaEvalTurnTraceRecorder recorder =
            new AlphaEvalTurnTraceRecorder(repository, Clock.fixed(NOW, ZoneOffset.UTC), RETENTION, "sha-81ff1e0");

    @Test
    void completedTurnPersistsAllStagesAndOrderedToolIo() {
        recorder.begin(USER, "Show revenue for last month");
        recorder.recordSimpleChat(false);
        recorder.recordRouting("ANALYTICS", "T2_COMPLEX");
        recorder.recordWorkflowState("IDLE");
        recorder.recordSelectedTools(List.of("DateWindowFacadeTool", "AccountingFacadeTool"));
        recorder.recordPrompt(
                "assembled prompt",
                List.of(new EvalTurnTrace.ToolDefinitionTrace(
                        "resolveDateWindow", "Resolve a date window", "{\"type\":\"object\"}")));
        recorder.recordToolCall(
                "resolveDateWindow",
                "{\"expression\":\"last month\"}",
                "{\"startDate\":\"2026-08-01\",\"endDate\":\"2026-08-31\",\"shape\":\"calendar\"}",
                null,
                4);
        recorder.recordToolCall(
                "getRevenueByCustomer",
                "{\"startDate\":\"2026-08-01\",\"endDate\":\"2026-08-31\"}",
                "{\"customers\":[]}",
                null,
                8);

        recorder.recordAnswerSource("RE_RENDERED");
        recorder.complete("Revenue was $1,500.00.");

        EvalTurnTrace trace = savedTrace();
        assertThat(trace.turnId().version()).isEqualTo(7);
        assertThat(trace.startedAt()).isEqualTo(NOW);
        assertThat(trace.completedAt()).isEqualTo(NOW);
        assertThat(trace.expiresAt()).isEqualTo(NOW.plus(RETENTION));
        assertThat(trace.username()).isEqualTo("diana.rowe");
        assertThat(trace.userId()).isEqualTo(USER.userId());
        assertThat(trace.role()).isEqualTo("LOCATION_MANAGER");
        assertThat(trace.userMessage()).isEqualTo("Show revenue for last month");
        assertThat(trace.simpleChat()).isFalse();
        assertThat(trace.intent()).isEqualTo("ANALYTICS");
        assertThat(trace.modelTier()).isEqualTo("T2_COMPLEX");
        assertThat(trace.workflowState()).isEqualTo("IDLE");
        assertThat(trace.selectedTools()).containsExactly("DateWindowFacadeTool", "AccountingFacadeTool");
        assertThat(trace.systemPrompt()).isEqualTo("assembled prompt");
        assertThat(trace.offeredTools())
                .extracting(EvalTurnTrace.ToolDefinitionTrace::name)
                .containsExactly("resolveDateWindow");
        assertThat(trace.toolCalls())
                .extracting(EvalTurnTrace.ToolCallTrace::sequence)
                .containsExactly(1, 2);
        assertThat(trace.toolCalls())
                .extracting(EvalTurnTrace.ToolCallTrace::name)
                .containsExactly("resolveDateWindow", "getRevenueByCustomer");
        assertThat(trace.finalResponse()).isEqualTo("Revenue was $1,500.00.");
        assertThat(trace.error()).isNull();
        // #1806: the build that answered, so a run measured across a mid-run deploy can say so.
        assertThat(trace.serverBuild()).isEqualTo("sha-81ff1e0");
        // #1816: how the reply was produced, so grading can tell answered from deflected.
        assertThat(trace.answerSource()).isEqualTo("RE_RENDERED");
        assertThat(recorder.hasActiveTurn()).isFalse();
    }

    @Test
    @DisplayName("#2075: recordMessage stamps the conversation and message id onto the saved trace")
    void recordMessageStampsConversationAndMessageIdOntoTheSavedTrace() {
        UUID conversationId = UUID.fromString("0199b1be-7080-7000-8000-000000000abc");
        UUID messageId = UUID.fromString("0199b1be-7080-7000-8000-000000000def");
        recorder.begin(USER, "how many mechanics");
        recorder.recordMessage(conversationId, messageId);

        recorder.complete("26 mechanics");

        EvalTurnTrace trace = savedTrace();
        assertThat(trace.conversationId()).isEqualTo(conversationId);
        assertThat(trace.messageId()).isEqualTo(messageId);
    }

    @Test
    @DisplayName("#2075: the ephemeral path (both ids null) still records a trace with both null")
    void recordMessageWithNullIds_savesTraceWithNullIds() {
        recorder.begin(USER, "ephemeral question");
        recorder.recordMessage(null, null);

        recorder.complete("answer");

        EvalTurnTrace trace = savedTrace();
        assertThat(trace.conversationId()).isNull();
        assertThat(trace.messageId()).isNull();
    }

    @Test
    void answerSourceIsPerTurnAndNotCarriedIntoTheNext() {
        // #1816: the builder is created at begin(), so a source recorded on one turn cannot leak
        // into the next — asserted rather than assumed, because the gate fails a run on it.
        recorder.begin(USER, "first");
        recorder.recordAnswerSource("CONTENT");
        recorder.complete("answer");
        recorder.begin(USER, "second");
        recorder.fail(new IllegalStateException("model down"));

        ArgumentCaptor<EvalTurnTrace> captor = ArgumentCaptor.forClass(EvalTurnTrace.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).answerSource()).isEqualTo("CONTENT");
        assertThat(captor.getAllValues().get(1).answerSource()).isNull();
        assertThat(captor.getAllValues().get(1).error()).contains("model down");
    }

    @Test
    void failedTurnPersistsErrorAndAlwaysClearsActiveState() {
        recorder.begin(USER, "broken turn");

        recorder.fail(new IllegalStateException("model unavailable"));

        EvalTurnTrace trace = savedTrace();
        assertThat(trace.finalResponse()).isNull();
        assertThat(trace.error()).isEqualTo("IllegalStateException: model unavailable");
        assertThat(recorder.hasActiveTurn()).isFalse();
    }

    @Test
    void persistenceFailureNeverLeaksTurnState() {
        doThrow(new IllegalStateException("database unavailable"))
                .when(repository)
                .save(org.mockito.ArgumentMatchers.any());
        recorder.begin(USER, "safe failure");

        recorder.complete("answer");

        assertThat(recorder.hasActiveTurn()).isFalse();
    }

    private EvalTurnTrace savedTrace() {
        ArgumentCaptor<EvalTurnTrace> captor = ArgumentCaptor.forClass(EvalTurnTrace.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    // ── #1850: a turn must be reachable from another thread ─────────────────

    @Test
    @DisplayName("a turn opened on one thread records nothing from another until it is bound")
    void turnIsThreadBoundUntilRebound() throws Exception {
        recorder.begin(USER, "stream me");
        Object handle = recorder.currentTurnHandle();
        assertThat(handle).as("the open turn is handed out as a handle").isNotNull();

        // The failure this fixes: a Reactor thread records onto nothing.
        runOnAnotherThread(() -> recorder.recordToolCall("getAgedReceivables", "{}", "{}", null, 5));
        // Bound, the same call from the same kind of thread lands.
        runOnAnotherThread(() -> recorder.runWithTurn(
                handle, () -> recorder.recordToolCall("getRevenueByCustomer", "{}", "{}", null, 7)));
        runOnAnotherThread(() -> recorder.runWithTurn(handle, () -> recorder.complete("done")));

        EvalTurnTrace trace = savedTrace();
        assertThat(trace.toolCalls())
                .extracting(EvalTurnTrace.ToolCallTrace::name)
                .as("only the bound call was recorded")
                .containsExactly("getRevenueByCustomer");
        assertThat(trace.finalResponse()).isEqualTo("done");
    }

    @Test
    @DisplayName("runWithTurn restores whatever the thread had bound before, including nothing")
    void runWithTurnRestoresPreviousBinding() {
        recorder.begin(USER, "outer turn");
        Object outer = recorder.currentTurnHandle();

        recorder.runWithTurn(null, () -> {});
        assertThat(recorder.currentTurnHandle())
                .as("a null handle leaves the binding alone")
                .isEqualTo(outer);

        AlphaEvalTurnTraceRecorder other =
                new AlphaEvalTurnTraceRecorder(repository, Clock.fixed(NOW, ZoneOffset.UTC), RETENTION, "sha-test");
        other.begin(USER, "another turn");
        Object otherHandle = other.currentTurnHandle();
        recorder.runWithTurn(
                otherHandle, () -> assertThat(recorder.currentTurnHandle()).isEqualTo(otherHandle));
        assertThat(recorder.currentTurnHandle())
                .as("the outer turn is back afterwards")
                .isEqualTo(outer);
    }

    @Test
    @DisplayName("a thread with no turn bound records nothing and does not throw")
    void recordingWithoutATurnIsSafe() {
        recorder.recordToolCall("getAgedReceivables", "{}", "{}", null, 1);
        recorder.recordAnswerSource("CONTENT");
        recorder.complete("no turn was open");

        verifyNoInteractions(repository);
    }

    private static void runOnAnotherThread(Runnable action) throws Exception {
        Thread thread = new Thread(action);
        thread.start();
        thread.join();
    }

    // ── ADR-0069 §9: the scope on the trace ─────────────────────────────────

    private static ScopeSet scope() {
        return new ScopeSet(
                List.of(new ScopeSet.Seed("workorder", MatchKind.IDENTIFIER)),
                List.of("estimate", "invoice"),
                List.of("workorder"),
                List.of(
                        new ScopeSet.ScopeTool(
                                "WorkorderFacadeTool", NodeAttributes.ToolSource.FACADE, 1, Access.READS),
                        new ScopeSet.ScopeTool(
                                "workorder_getworkorder", NodeAttributes.ToolSource.DISCOVERED, 1, Access.READS)),
                List.of("workorder.status-lifecycle", "workorder.public"),
                List.of("workorders.list"),
                List.of("workorder.DRAFT"),
                List.of(),
                ScopeSet.Confidence.HIGH,
                "c878c7206d2ed660",
                Instant.parse("2026-09-30T12:00:00Z"));
    }

    private AlphaEvalTurnTraceRecorder scopeRecorder(ScopeGraphProperties properties, MeterRegistry meters) {
        return new AlphaEvalTurnTraceRecorder(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                RETENTION,
                "sha-81ff1e0",
                properties,
                new ScopeMetrics(properties, meters));
    }

    private static ScopeGraphProperties mode(ScopeGraphProperties.Mode mode, ScopeGraphProperties.Consumer... enforce) {
        return new ScopeGraphProperties(mode, List.of(enforce), 0, 0, 0);
    }

    @Test
    @DisplayName("ADR-0069: a turn with no recorded scope (mode off, simple chat) persists a null scope")
    void noScopeRecordedPersistsNullScope() {
        recorder.begin(USER, "hello");
        recorder.recordToolCall("getWorkorder", "{}", "{}", null, 1);
        recorder.complete("hi");

        assertThat(savedTrace().scope()).isNull();
    }

    @Test
    @DisplayName(
            "ADR-0069: a recorded scope is persisted as keys, kinds and counts, with the in-scope shares computed at completion")
    void scopeIsTracedWithInScopeShares() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AlphaEvalTurnTraceRecorder shadow = scopeRecorder(mode(ScopeGraphProperties.Mode.SHADOW), meters);

        shadow.begin(USER, "is WO-20391 billed?");
        shadow.recordScope(scope());
        // A facade callback is named after its @Tool method; the scope knows the facade's class name.
        shadow.recordToolCall("getWorkorder", "{}", "{}", null, 3);
        shadow.recordCalledCatalogTool("WorkorderFacadeTool");
        shadow.recordToolCall("getInvoice", "{}", "{}", null, 4);
        shadow.recordCalledCatalogTool("InvoiceFacadeTool");
        // A call to a tool that was never offered has no catalog name: called, and in no scope.
        shadow.recordToolCall("inventedTool", "{}", null, "Unknown tool", 0);
        shadow.recordRetrievedDocuments(List.of("workorder.public", "billing.invoices"));
        shadow.recordRetrievedDocuments(List.of("workorder.public", "workorder.status-lifecycle"));
        shadow.complete("yes");

        ScopeTrace traced = savedTrace().scope();
        assertThat(traced).isNotNull();
        assertThat(traced.mode()).isEqualTo("SHADOW");
        assertThat(traced.enforced()).isEmpty();
        assertThat(traced.graphHash()).isEqualTo("c878c7206d2ed660");
        assertThat(traced.graphBuiltAt()).isEqualTo(Instant.parse("2026-09-30T12:00:00Z"));
        assertThat(traced.confidence()).isEqualTo("HIGH");
        assertThat(traced.seeds()).containsExactly(new ScopeTrace.SeedTrace("workorder", "IDENTIFIER"));
        assertThat(traced.entityCount()).isEqualTo(3);
        assertThat(traced.toolCount()).isEqualTo(2);
        assertThat(traced.documentCount()).isEqualTo(2);
        assertThat(traced.screenCount()).isEqualTo(1);
        // Nothing recorded for the consumers: shadow adds nothing and narrows nothing.
        assertThat(traced.addedTools()).isZero();
        assertThat(traced.ragFilterApplied()).isFalse();
        assertThat(traced.calledTools()).isEqualTo(3);
        assertThat(traced.calledToolsInScope()).isEqualTo(1);
        // Three distinct documents across two retrievals; two of them are in scope.
        assertThat(traced.retrievedDocs()).isEqualTo(3);
        assertThat(traced.retrievedDocsInScope()).isEqualTo(2);
        // The scope holds nothing the caller typed.
        assertThat(traced.toString()).doesNotContain("WO-20391", "billed");

        assertThat(meters.get("mcp.scope.called_tool")
                        .tag("in_scope", "true")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(meters.get("mcp.scope.called_tool")
                        .tag("in_scope", "false")
                        .counter()
                        .count())
                .isEqualTo(2.0);
        assertThat(meters.get("mcp.scope.retrieved_doc")
                        .tag("in_scope", "true")
                        .counter()
                        .count())
                .isEqualTo(2.0);
        assertThat(meters.get("mcp.scope.retrieved_doc")
                        .tag("in_scope", "false")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("ADR-0069: with no retrieval observed the retrieved-document shares stay null, not zero")
    void noRetrievalObservedLeavesDocumentSharesNull() {
        recorder.begin(USER, "the work order");
        recorder.recordScope(scope());
        recorder.complete("ok");

        ScopeTrace traced = savedTrace().scope();
        assertThat(traced.retrievedDocs()).isNull();
        assertThat(traced.retrievedDocsInScope()).isNull();
        assertThat(traced.calledTools()).isZero();
        assertThat(traced.calledToolsInScope()).isZero();
    }

    @Test
    @DisplayName("ADR-0069 §6: what the consumers did is traced as the added-tool count and the RAG filter flag")
    void consumerOutcomeIsTraced() {
        AlphaEvalTurnTraceRecorder enforce = scopeRecorder(
                mode(
                        ScopeGraphProperties.Mode.ENFORCE,
                        ScopeGraphProperties.Consumer.RAG,
                        ScopeGraphProperties.Consumer.TOOLS),
                new SimpleMeterRegistry());

        enforce.begin(USER, "the work order WO-20391");
        enforce.recordScope(scope());
        enforce.recordScopeConsumers(List.of("WorkorderFacadeTool", "workorder_getworkorder"), true);
        enforce.complete("ok");

        ScopeTrace traced = savedTrace().scope();
        assertThat(traced.enforced()).containsExactly("RAG", "TOOLS");
        assertThat(traced.addedTools()).isEqualTo(2);
        assertThat(traced.ragFilterApplied()).isTrue();
    }

    @Test
    @DisplayName("ADR-0069: in enforce the trace names the enforced consumers; a failed turn still carries its scope")
    void enforceNamesConsumersAndFailedTurnKeepsScope() {
        AlphaEvalTurnTraceRecorder enforce = scopeRecorder(
                mode(ScopeGraphProperties.Mode.ENFORCE, ScopeGraphProperties.Consumer.RAG), new SimpleMeterRegistry());

        enforce.begin(USER, "the work order");
        enforce.recordScope(scope());
        enforce.fail(new IllegalStateException("model unavailable"));

        EvalTurnTrace trace = savedTrace();
        assertThat(trace.error()).contains("model unavailable");
        assertThat(trace.scope().mode()).isEqualTo("ENFORCE");
        assertThat(trace.scope().enforced()).containsExactly("RAG");
    }

    @Test
    @DisplayName(
            "ADR-0069: the scope travels with the turn handle, so a share recorded on another thread lands on the same trace")
    void scopeSharesRecordedAcrossThreads() throws Exception {
        recorder.begin(USER, "the work order");
        recorder.recordScope(scope());
        Object handle = recorder.currentTurnHandle();

        runOnAnotherThread(() -> recorder.runWithTurn(handle, () -> {
            recorder.recordToolCall("getWorkorder", "{}", "{}", null, 2);
            recorder.recordCalledCatalogTool("WorkorderFacadeTool");
            recorder.recordRetrievedDocuments(List.of("workorder.public"));
        }));
        recorder.complete("ok");

        ScopeTrace traced = savedTrace().scope();
        assertThat(traced.calledToolsInScope()).isEqualTo(1);
        assertThat(traced.retrievedDocsInScope()).isEqualTo(1);
    }
}

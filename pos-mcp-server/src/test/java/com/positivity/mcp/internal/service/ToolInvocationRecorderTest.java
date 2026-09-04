package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.config.CurrentUserContext;
import com.positivity.mcp.internal.eval.AlphaEvalTurnTraceRecorder;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * #1422: per-execution invocation logging must attribute a real
 * {@code tool_id}, never fail the
 * wrapped tool call, and hit the repository once per distinct lookup name.
 */
class ToolInvocationRecorderTest {

    private static final UUID TOOL_ID = UUID.fromString("01a01e00-0000-7000-8000-000000000042");

    private final ToolAuditService auditService = mock(ToolAuditService.class);
    private final ToolMetadataRepository repository = mock(ToolMetadataRepository.class);
    private final AlphaEvalTurnTraceRecorder traceRecorder = mock(AlphaEvalTurnTraceRecorder.class);
    private final RequestScopedUserContext userContext = new RequestScopedUserContext();
    private final ToolInvocationRecorder recorder =
            new ToolInvocationRecorder(auditService, repository, userContext, traceRecorder);

    @BeforeEach
    void setUp() {
        userContext.set(new CurrentUserContext(
                "diana.rowe",
                UUID.fromString("01960010-0000-7000-8000-000000000002"),
                "LOCATION_MANAGER",
                Set.of("LOCATION_MANAGER"),
                Set.of(),
                Set.of("pricing:promotion:manage")));
    }

    @AfterEach
    void cleanup() {
        userContext.clear();
    }

    @Test
    @DisplayName("successful call logs tool_id, username and success=true")
    void wrap_success_logsResolvedToolId() {
        when(repository.findToolIdByName("OrderFacadeTool")).thenReturn(Optional.of(TOOL_ID));
        ToolCallback wrapped = recorder.wrap(fixedCallback("ok"), "OrderFacadeTool");

        assertThat(wrapped.call("{}")).isEqualTo("ok");

        verify(auditService).logToolExecution(eq(TOOL_ID), eq("diana.rowe"), eq(true), eq(false), anyInt(), isNull());
    }

    @Test
    @DisplayName("successful callbacks append exact arguments and results to the active eval turn in call order")
    void wrap_success_recordsFullTurnIoInOrder() {
        when(repository.findToolIdByName("OrderFacadeTool")).thenReturn(Optional.of(TOOL_ID));
        ToolCallback first = recorder.wrap(callback("first", input -> "first-result"), "OrderFacadeTool");
        ToolCallback second = recorder.wrap(callback("second", input -> "second-result"), "OrderFacadeTool");

        first.call("{\"startDate\":\"2026-08-01\"}");
        second.call("{\"customerId\":\"TRACKB-C1\"}");

        InOrder ordered = inOrder(traceRecorder);
        ordered.verify(traceRecorder)
                .recordToolCall(
                        eq("first"), eq("{\"startDate\":\"2026-08-01\"}"), eq("first-result"), isNull(), anyInt());
        ordered.verify(traceRecorder)
                .recordToolCall(
                        eq("second"), eq("{\"customerId\":\"TRACKB-C1\"}"), eq("second-result"), isNull(), anyInt());
    }

    @Test
    @DisplayName("failing call logs success=false with the failure description and rethrows")
    void wrap_failure_logsErrorTypeAndRethrows() {
        when(repository.findToolIdByName("OrderFacadeTool")).thenReturn(Optional.of(TOOL_ID));
        ToolCallback wrapped = recorder.wrap(throwingCallback(new IllegalStateException("boom")), "OrderFacadeTool");

        assertThatThrownBy(() -> wrapped.call("{}")).isInstanceOf(IllegalStateException.class);

        verify(auditService)
                .logToolExecution(
                        eq(TOOL_ID),
                        eq("diana.rowe"),
                        eq(false),
                        eq(false),
                        anyInt(),
                        eq("IllegalStateException: boom"));
        verify(traceRecorder)
                .recordToolCall(eq("sample"), eq("{}"), isNull(), eq("IllegalStateException: boom"), anyInt());
    }

    // ─── describeFailure (#1660) ────────────────────────────────────────────

    /**
     * The defect this closes. {@code ReflectiveToolCallback} wraps every tool
     * failure in the same
     * {@code IllegalStateException("Tool method failed: <name>")}, so recording the
     * thrown type
     * stored {@code IllegalStateException} for every failure alike and gate q04's
     * could not be
     * diagnosed from the table. The cause is the part that differs.
     */
    @Test
    @DisplayName("a wrapped tool failure records the root cause, not the uniform wrapper type")
    void wrap_failure_recordsRootCauseRatherThanWrapper() {
        when(repository.findToolIdByName("InvoiceFacadeTool")).thenReturn(Optional.of(TOOL_ID));
        RuntimeException wrapper = new IllegalStateException(
                "Tool method failed: searchInvoices", new java.net.SocketTimeoutException("Read timed out"));
        ToolCallback wrapped = recorder.wrap(throwingCallback(wrapper), "InvoiceFacadeTool");

        assertThatThrownBy(() -> wrapped.call("{}")).isSameAs(wrapper);

        verify(auditService)
                .logToolExecution(
                        eq(TOOL_ID),
                        eq("diana.rowe"),
                        eq(false),
                        eq(false),
                        anyInt(),
                        eq("SocketTimeoutException: Read timed out"));
    }

    @Test
    @DisplayName("describeFailure walks to the deepest cause")
    void describeFailure_walksToDeepestCause() {
        Throwable deepest = new IllegalArgumentException("bad window");
        Throwable middle = new RuntimeException("adapter failed", deepest);
        Throwable top = new IllegalStateException("Tool method failed: x", middle);

        assertThat(ToolInvocationRecorder.describeFailure(top)).isEqualTo("IllegalArgumentException: bad window");
    }

    @Test
    @DisplayName("describeFailure falls back to the type when the cause carries no message")
    void describeFailure_withoutMessage_usesTypeAlone() {
        assertThat(ToolInvocationRecorder.describeFailure(new IllegalStateException("top", new NullPointerException())))
                .isEqualTo("NullPointerException");
    }

    /**
     * {@code error_type} is {@code VARCHAR(200)} (V6). An overflowing value would
     * fail the audit
     * insert, turning a diagnostic improvement into a new failure on the tool path.
     */
    @Test
    @DisplayName("describeFailure truncates to the error_type column width")
    void describeFailure_truncatesToColumnWidth() {
        String described = ToolInvocationRecorder.describeFailure(new IllegalStateException("x".repeat(500)));

        assertThat(described).hasSize(200).endsWith("...");
    }

    /** A self-referential chain must terminate rather than spin. */
    @Test
    @DisplayName("describeFailure terminates on a cyclic cause chain")
    void describeFailure_onCyclicChain_terminates() {
        Throwable first = new IllegalStateException("first");
        Throwable second = new IllegalStateException("second", first);
        first.initCause(second);

        assertThat(ToolInvocationRecorder.describeFailure(first)).isNotBlank();
    }

    @Test
    @DisplayName("unresolvable tool name still logs, with a null tool_id")
    void wrap_unknownName_logsWithNullToolId() {
        when(repository.findToolIdByName("GhostTool")).thenReturn(Optional.empty());
        ToolCallback wrapped = recorder.wrap(fixedCallback("ok"), "GhostTool");

        wrapped.call("{}");

        verify(auditService).logToolExecution(isNull(), eq("diana.rowe"), eq(true), eq(false), anyInt(), isNull());
    }

    @Test
    @DisplayName("tool id resolution is cached — one repository lookup per name")
    void resolution_isCachedPerName() {
        when(repository.findToolIdByName("OrderFacadeTool")).thenReturn(Optional.of(TOOL_ID));
        ToolCallback wrapped = recorder.wrap(fixedCallback("ok"), "OrderFacadeTool");

        wrapped.call("{}");
        wrapped.call("{}");
        wrapped.call("{}");

        verify(repository, times(1)).findToolIdByName("OrderFacadeTool");
        verify(auditService, times(3))
                .logToolExecution(eq(TOOL_ID), eq("diana.rowe"), eq(true), eq(false), anyInt(), isNull());
    }

    @Test
    @DisplayName("a failing audit write never fails the tool call")
    void auditFailure_neverFailsTheToolCall() {
        when(repository.findToolIdByName("OrderFacadeTool")).thenReturn(Optional.of(TOOL_ID));
        doThrow(new IllegalStateException("db down"))
                .when(auditService)
                .logToolExecution(any(), any(), anyBoolean(), anyBoolean(), anyInt(), any());
        ToolCallback wrapped = recorder.wrap(fixedCallback("ok"), "OrderFacadeTool");

        assertThat(wrapped.call("{}")).isEqualTo("ok");
    }

    @Test
    @DisplayName("no request-scoped user falls back to 'unknown', not a failure")
    void missingUserContext_logsUnknownUser() {
        userContext.clear();
        when(repository.findToolIdByName("OrderFacadeTool")).thenReturn(Optional.of(TOOL_ID));
        ToolCallback wrapped = recorder.wrap(fixedCallback("ok"), "OrderFacadeTool");

        wrapped.call("{}");

        verify(auditService).logToolExecution(eq(TOOL_ID), eq("unknown"), eq(true), eq(false), anyInt(), isNull());
    }

    @Test
    @DisplayName("record() logs a direct (non-callback) execution — the NLTI confirm path")
    void record_logsDirectExecution() {
        when(repository.findToolIdByName("price_deletepromotioneligibilityrule"))
                .thenReturn(Optional.of(TOOL_ID));

        recorder.record("price_deletepromotioneligibilityrule", true, 123, null);

        verify(auditService).logToolExecution(eq(TOOL_ID), eq("diana.rowe"), eq(true), eq(false), eq(123), isNull());
    }

    private static ToolCallback fixedCallback(String result) {
        return callback("sample", input -> result);
    }

    private static ToolCallback throwingCallback(RuntimeException exception) {
        return callback("sample", input -> {
            throw exception;
        });
    }

    private static ToolCallback callback(String name, java.util.function.Function<String, String> body) {
        ToolDefinition definition = DefaultToolDefinition.builder()
                .name(name)
                .description("sample")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return body.apply(toolInput);
            }
        };
    }
}

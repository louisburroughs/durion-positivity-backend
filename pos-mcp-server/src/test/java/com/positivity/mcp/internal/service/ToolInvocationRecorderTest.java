package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import com.positivity.mcp.service.CurrentUserContext;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * #1422: per-execution invocation logging must attribute a real {@code tool_id}, never fail the
 * wrapped tool call, and hit the repository once per distinct lookup name.
 */
class ToolInvocationRecorderTest {

    private static final UUID TOOL_ID = UUID.fromString("01a01e00-0000-7000-8000-000000000042");

    private final ToolAuditService auditService = mock(ToolAuditService.class);
    private final ToolMetadataRepository repository = mock(ToolMetadataRepository.class);
    private final RequestScopedUserContext userContext = new RequestScopedUserContext();
    private final ToolInvocationRecorder recorder = new ToolInvocationRecorder(auditService, repository, userContext);

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
    @DisplayName("failing call logs success=false with the exception type and rethrows")
    void wrap_failure_logsErrorTypeAndRethrows() {
        when(repository.findToolIdByName("OrderFacadeTool")).thenReturn(Optional.of(TOOL_ID));
        ToolCallback wrapped = recorder.wrap(throwingCallback(new IllegalStateException("boom")), "OrderFacadeTool");

        assertThatThrownBy(() -> wrapped.call("{}")).isInstanceOf(IllegalStateException.class);

        verify(auditService)
                .logToolExecution(
                        eq(TOOL_ID), eq("diana.rowe"), eq(false), eq(false), anyInt(), eq("IllegalStateException"));
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
        return callback(input -> result);
    }

    private static ToolCallback throwingCallback(RuntimeException exception) {
        return callback(input -> {
            throw exception;
        });
    }

    private static ToolCallback callback(java.util.function.Function<String, String> body) {
        ToolDefinition definition = DefaultToolDefinition.builder()
                .name("sample")
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

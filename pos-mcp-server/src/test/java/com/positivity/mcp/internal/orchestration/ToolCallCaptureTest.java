package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Unit tests for {@link ToolCallCapture} (#2075): the name is reported to the sink before
 * delegating, on both {@code call} overloads, even when the delegate or the sink itself throws.
 */
@DisplayName("ToolCallCapture (#2075)")
class ToolCallCaptureTest {

    @Test
    @DisplayName("call(String): the tool name is recorded before delegating, and the result passes through")
    void call_recordsNameBeforeDelegating() {
        ToolCallback delegate = stubCallback("ping", input -> "pong");
        List<String> recorded = new ArrayList<>();

        ToolCallback wrapped =
                ToolCallCapture.wrap(List.of(delegate), recorded::add).get(0);
        String result = wrapped.call("{}");

        assertThat(result).isEqualTo("pong");
        assertThat(recorded).containsExactly("ping");
    }

    @Test
    @DisplayName(
            "call(String, ToolContext): the tool name is recorded before delegating, and the result passes through")
    void callWithContext_recordsNameBeforeDelegating() {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition()).thenReturn(definitionNamed("lookup"));
        ToolContext context = new ToolContext(Map.of());
        when(delegate.call("{}", context)).thenReturn("result");
        List<String> recorded = new ArrayList<>();

        ToolCallback wrapped =
                ToolCallCapture.wrap(List.of(delegate), recorded::add).get(0);
        String result = wrapped.call("{}", context);

        assertThat(result).isEqualTo("result");
        assertThat(recorded).containsExactly("lookup");
        verify(delegate).call("{}", context);
    }

    @Test
    @DisplayName("a delegate that throws is still recorded, before the exception propagates")
    void call_delegateThrows_stillRecordedFirst() {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition()).thenReturn(definitionNamed("boom"));
        when(delegate.call(any())).thenThrow(new IllegalStateException("boom failed"));
        List<String> recorded = new ArrayList<>();

        ToolCallback wrapped =
                ToolCallCapture.wrap(List.of(delegate), recorded::add).get(0);

        assertThatThrownBy(() -> wrapped.call("{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom failed");
        assertThat(recorded).containsExactly("boom");
    }

    @Test
    @DisplayName("call(String, ToolContext): a delegate that throws is still recorded first")
    void callWithContext_delegateThrows_stillRecordedFirst() {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition()).thenReturn(definitionNamed("boom"));
        ToolContext context = new ToolContext(Map.of());
        when(delegate.call("{}", context)).thenThrow(new IllegalStateException("boom failed"));
        List<String> recorded = new ArrayList<>();

        ToolCallback wrapped =
                ToolCallCapture.wrap(List.of(delegate), recorded::add).get(0);

        assertThatThrownBy(() -> wrapped.call("{}", context)).isInstanceOf(IllegalStateException.class);
        assertThat(recorded).containsExactly("boom");
    }

    @Test
    @DisplayName("a sink exception is swallowed and never fails the call")
    void call_sinkThrows_callStillSucceeds() {
        ToolCallback delegate = stubCallback("ping", input -> "pong");
        Consumer<String> failingSink = name -> {
            throw new RuntimeException("sink boom");
        };

        ToolCallback wrapped =
                ToolCallCapture.wrap(List.of(delegate), failingSink).get(0);

        assertThat(wrapped.call("{}")).isEqualTo("pong");
    }

    @Test
    @DisplayName("repeated calls to the same wrapped callback each report the name (duplicates kept)")
    void call_repeatedCalls_eachReported() {
        ToolCallback delegate = stubCallback("ping", input -> "pong");
        List<String> recorded = new ArrayList<>();
        ToolCallback wrapped =
                ToolCallCapture.wrap(List.of(delegate), recorded::add).get(0);

        wrapped.call("{}");
        wrapped.call("{}");

        assertThat(recorded).containsExactly("ping", "ping");
    }

    @Test
    @DisplayName("an empty callback list is returned as-is")
    void wrap_emptyList_returnedAsIs() {
        List<ToolCallback> empty = List.of();

        assertThat(ToolCallCapture.wrap(empty, name -> {})).isSameAs(empty);
    }

    @Test
    @DisplayName("getToolDefinition and getToolMetadata delegate unchanged")
    void definitionAndMetadata_delegate() {
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = definitionNamed("ping");
        ToolMetadata metadata = ToolMetadata.builder().build();
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(delegate.getToolMetadata()).thenReturn(metadata);

        ToolCallback wrapped =
                ToolCallCapture.wrap(List.of(delegate), name -> {}).get(0);

        assertThat(wrapped.getToolDefinition()).isSameAs(definition);
        assertThat(wrapped.getToolMetadata()).isSameAs(metadata);
    }

    private static ToolDefinition definitionNamed(String name) {
        return ToolDefinition.builder()
                .name(name)
                .description("description")
                .inputSchema("{}")
                .build();
    }

    private static ToolCallback stubCallback(String name, Function<String, String> onCall) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(definitionNamed(name));
        when(callback.call(any())).thenAnswer(invocation -> onCall.apply(invocation.getArgument(0)));
        return callback;
    }
}

package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import com.positivity.mcp.internal.service.AlphaEvalTurnTraceRecorder;
import com.positivity.mcp.internal.service.RequestScopedUserContext;
import com.positivity.mcp.internal.service.ToolAuditService;
import com.positivity.mcp.internal.service.ToolInvocationRecorder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Runs the real {@code DefaultToolCallingManager} under the bounded wrapper against a real reflective
 * callback, so the unknown-tool path is exercised exactly as the chat loop drives it (#1831).
 */
class BoundedToolCallingManagerTest {

    private final List<ToolCallback> callbacks = SpringAiToolCallbackResolver.fromObjects(List.of(new EchoTool()));

    private final BoundedToolCallingManager manager =
            new BoundedToolCallingManager(ToolCallingManager.builder().build());

    @Test
    @DisplayName("a call to a tool that was never offered is answered with a correction, not thrown (#1831)")
    void unknownToolIsAnsweredNotThrown() {
        // Alpha 2026-09-06 08:38Z, gpt-oss:120b on q04: the model called a tool named "calls"
        // and DefaultToolCallingManager's IllegalStateException ended the turn in the ladder.
        Prompt prompt = prompt();
        ChatResponse response = response(call("1", "calls", "{}"));

        ToolExecutionResult result = manager.executeToolCalls(prompt, response);

        assertThat(result.returnDirect()).isFalse();
        List<Message> history = result.conversationHistory();
        assertThat(history).hasSize(prompt.getInstructions().size() + 2);
        assertThat(history.get(history.size() - 2))
                .isInstanceOf(AssistantMessage.class)
                .extracting(message -> ((AssistantMessage) message).getToolCalls())
                .isEqualTo(response.getResult().getOutput().getToolCalls());
        ToolResponseMessage toolResponses = (ToolResponseMessage) history.getLast();
        assertThat(toolResponses.getResponses()).singleElement().satisfies(toolResponse -> {
            assertThat(toolResponse.id()).isEqualTo("1");
            assertThat(toolResponse.name()).isEqualTo("calls");
            assertThat(toolResponse.responseData())
                    .contains("Unknown tool 'calls'")
                    .contains("1 tools offered")
                    .contains("exact name");
        });
    }

    @Test
    @DisplayName("offered calls still execute when an unknown one sits between them, responses stay in call order")
    void mixedCallsExecuteTheOfferedOnes() {
        ToolExecutionResult result = manager.executeToolCalls(
                prompt(),
                response(
                        call("a", "echo", "{\"text\":\"first\"}"),
                        call("b", "calls", "{}"),
                        call("c", "echo", "{\"text\":\"second\"}")));

        ToolResponseMessage toolResponses =
                (ToolResponseMessage) result.conversationHistory().getLast();
        assertThat(toolResponses.getResponses())
                .extracting(ToolResponseMessage.ToolResponse::id, ToolResponseMessage.ToolResponse::responseData)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("a", "echo:first"),
                        org.assertj.core.groups.Tuple.tuple(
                                "b", BoundedToolCallingManager.unknownToolMessage("calls", 1)),
                        org.assertj.core.groups.Tuple.tuple("c", "echo:second"));
    }

    @Test
    @DisplayName("offered calls alone go through the delegate untouched")
    void offeredCallsPassThrough() {
        ToolExecutionResult result =
                manager.executeToolCalls(prompt(), response(call("1", "echo", "{\"text\":\"x\"}")));

        ToolResponseMessage toolResponses =
                (ToolResponseMessage) result.conversationHistory().getLast();
        assertThat(toolResponses.getResponses())
                .singleElement()
                .extracting(ToolResponseMessage.ToolResponse::responseData)
                .isEqualTo("echo:x");
    }

    @Test
    @DisplayName("an unknown-only round at the round-trip cap degrades the turn instead of showing the correction")
    void unknownOnlyRoundAtTheCapDegrades() {
        // The correction is written for the model. Returned directly it would be the user's answer.
        Prompt prompt = promptAtTheCap();

        assertThatThrownBy(() -> manager.executeToolCalls(prompt, response(call("1", "calls", "{}"))))
                .isInstanceOf(BoundedToolCallingManager.UnknownToolLoopException.class)
                .hasMessageContaining("not offered");
    }

    @Test
    @DisplayName("a mixed round at the cap returns directly with only the results real tools produced")
    void mixedRoundAtTheCapReturnsOnlyExecutedResults() {
        ToolExecutionResult result = manager.executeToolCalls(
                promptAtTheCap(), response(call("a", "calls", "{}"), call("b", "echo", "{\"text\":\"kept\"}")));

        assertThat(result.returnDirect()).isTrue();
        ToolResponseMessage toolResponses =
                (ToolResponseMessage) result.conversationHistory().getLast();
        assertThat(toolResponses.getResponses())
                .extracting(ToolResponseMessage.ToolResponse::responseData)
                .containsExactly("echo:kept");
    }

    /** A prompt already holding the rounds that bring the next one to {@code MAX_TOOL_TURNS}. */
    private Prompt promptAtTheCap() {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("question"));
        for (int i = 0; i < BoundedToolCallingManager.MAX_TOOL_TURNS - 1; i++) {
            messages.add(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse("t" + i, "echo", "earlier")))
                    .build());
        }
        return new Prompt(messages, options());
    }

    @Test
    @DisplayName("the unknown call lands in the eval turn trace with the correction as its error")
    void unknownCallIsTraced() {
        AlphaEvalTurnTraceRecorder traceRecorder = mock(AlphaEvalTurnTraceRecorder.class);
        ToolInvocationRecorder recorder = new ToolInvocationRecorder(
                mock(ToolAuditService.class),
                mock(ToolMetadataRepository.class),
                mock(RequestScopedUserContext.class),
                traceRecorder);
        BoundedToolCallingManager tracing =
                new BoundedToolCallingManager(ToolCallingManager.builder().build(), recorder);

        tracing.executeToolCalls(prompt(), response(call("1", "calls", "{\"x\":1}")));

        verify(traceRecorder)
                .recordToolCall(eq("calls"), eq("{\"x\":1}"), isNull(), contains("Unknown tool 'calls'"), anyInt());
    }

    @Test
    @DisplayName("a response without tool calls is still the delegate's error, unchanged")
    void noToolCallsIsStillTheDelegatesError() {
        ChatResponse noCalls = new ChatResponse(List.of(new Generation(new AssistantMessage("plain answer"))));

        assertThatThrownBy(() -> manager.executeToolCalls(prompt(), noCalls))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No tool call requested");
    }

    private Prompt prompt() {
        return new Prompt(List.of(new UserMessage("question")), options());
    }

    private ToolCallingChatOptions options() {
        return ToolCallingChatOptions.builder().toolCallbacks(callbacks).build();
    }

    private static ChatResponse response(AssistantMessage.ToolCall... toolCalls) {
        AssistantMessage assistantMessage = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(toolCalls))
                .build();
        return new ChatResponse(List.of(new Generation(assistantMessage)));
    }

    private static AssistantMessage.ToolCall call(String id, String name, String arguments) {
        return new AssistantMessage.ToolCall(id, "function", name, arguments);
    }

    static final class EchoTool {
        @Tool(description = "echoes its argument")
        public String echo(@ToolParam(description = "text to echo") String text) {
            return "echo:" + text;
        }
    }
}

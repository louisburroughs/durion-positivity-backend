package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import com.positivity.mcp.internal.service.OpenApiToolProvider;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import reactor.core.publisher.Flux;

class SpringAiStreamingPosAssistantTest {

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ArgumentCaptor<List<Message>> messageListCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }

    /**
     * #1838: the streaming path now classifies the reply like the blocking one. The tokens here are
     * the shape gpt-oss:20b streamed on 2026-09-06 — protocol markup, not an answer.
     */
    @Test
    void chat_replacesStreamedHarmonyMarkupWithTheFallbackAndRecordsTheSource() {
        StreamingChatModel streamingChatModel =
                mock(StreamingChatModel.class, withSettings().extraInterfaces(ChatModel.class));
        QueryDocumentRetriever ragRetriever = mock(QueryDocumentRetriever.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        com.positivity.mcp.internal.service.ToolInvocationRecorder recorder =
                mock(com.positivity.mcp.internal.service.ToolInvocationRecorder.class);
        when(recorder.wrap(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(((ChatModel) streamingChatModel).getOptions())
                .thenReturn(OllamaChatOptions.builder()
                        .model("deepseek-v4-flash:0731")
                        .build());
        when(ragRetriever.retrieve(any())).thenReturn(List.of());
        when(chatMemory.get(any())).thenReturn(List.of());
        when(streamingChatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(
                        chatResponse("<|channel|>"),
                        chatResponse("analysis<|message|>"),
                        chatResponse("The user asked: who are our ten largest customers?")));

        SpringAiStreamingPosAssistant assistant = new SpringAiStreamingPosAssistant(
                streamingChatModel,
                () -> "base prompt",
                List.of(new PingTool()),
                ragRetriever,
                ignored -> chatMemory,
                null,
                recorder,
                null,
                null);

        List<String> tokens = assistant
                .chat("user-3::ROLE_ADMIN", "who are our ten largest customers", "ctx")
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(tokens).containsExactly(ChatResponseText.BLANK_RESPONSE_FALLBACK);
        verify(recorder).recordAnswerSource("PROTOCOL_MARKUP");
        ArgumentCaptor<List<org.springframework.ai.chat.messages.Message>> stored = messageListCaptor();
        verify(chatMemory, times(2)).add(eq("user-3::ROLE_ADMIN"), stored.capture());
        assertThat(stored.getAllValues().get(1).getFirst().getText())
                .as("memory stores what the client saw, not the markup")
                .isEqualTo(ChatResponseText.BLANK_RESPONSE_FALLBACK);
    }

    @Test
    void chat_usesRagRetrieverAndPersistsMemoryIdOnStreamCompletion() {
        StreamingChatModel streamingChatModel =
                mock(StreamingChatModel.class, withSettings().extraInterfaces(ChatModel.class));
        QueryDocumentRetriever ragRetriever = mock(QueryDocumentRetriever.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        OpenApiToolProvider openApiToolProvider = mock(OpenApiToolProvider.class);

        when(((ChatModel) streamingChatModel).getOptions())
                .thenReturn(OllamaChatOptions.builder().model("qwen3.5:cloud").build());
        when(openApiToolProvider.resolveToolCallbacks(any())).thenReturn(List.of());
        when(ragRetriever.retrieve("where is stock")).thenReturn(List.of(new Document("Inventory doc")));
        when(chatMemory.get("user-2::ROLE_TECH")).thenReturn(List.of(new AssistantMessage("previous turn")));
        when(streamingChatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(chatResponse("Hel"), chatResponse(""), chatResponse("lo")));

        SpringAiStreamingPosAssistant assistant = new SpringAiStreamingPosAssistant(
                streamingChatModel,
                () -> "base prompt",
                List.of(new PingTool()),
                ragRetriever,
                ignored -> chatMemory,
                openApiToolProvider,
                null,
                null,
                null);

        List<String> tokens = assistant
                .chat("user-2::ROLE_TECH", "where is stock", "ctx:role=TECH")
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(tokens).containsExactly("Hel", "lo");
        verify(ragRetriever).retrieve("where is stock");
        verify(openApiToolProvider).resolveToolCallbacks("where is stock");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(streamingChatModel).stream(promptCaptor.capture());
        List<Message> promptMessages = promptCaptor.getValue().getInstructions();
        assertThat(promptMessages).hasSize(3);
        assertThat(promptMessages.getFirst().getText()).isEqualTo("previous turn");
        assertThat(promptMessages.get(1).getText())
                .contains("base prompt")
                .contains("ctx:role=TECH")
                .contains("Relevant retrieved context:")
                .contains("Inventory doc");
        assertThat(promptMessages.get(2).getText()).isEqualTo("where is stock");

        ArgumentCaptor<List<Message>> persistedCalls = messageListCaptor();
        verify(chatMemory, times(2)).add(eq("user-2::ROLE_TECH"), persistedCalls.capture());
        assertThat(persistedCalls.getAllValues()).hasSize(2);

        List<Message> firstCall = persistedCalls.getAllValues().getFirst();
        assertThat(firstCall).hasSize(1);
        assertThat(firstCall.getFirst().getText()).isEqualTo("where is stock");

        List<Message> secondCall = persistedCalls.getAllValues().get(1);
        assertThat(secondCall).hasSize(1);
        assertThat(secondCall.getFirst()).isInstanceOf(AssistantMessage.class);
        assertThat(secondCall.getFirst().getText()).isEqualTo("Hello");
    }

    @Test
    void chat_buildsProviderSpecificToolCallingOptionsCarryingModel() {
        // The concrete Ollama bean implements both StreamingChatModel and ChatModel;
        // the runtime
        // options must copy its OllamaChatOptions default so OllamaChatModel's direct
        // cast to
        // OllamaChatOptions succeeds and the configured model is preserved.
        StreamingChatModel streamingChatModel =
                mock(StreamingChatModel.class, withSettings().extraInterfaces(ChatModel.class));
        QueryDocumentRetriever ragRetriever = mock(QueryDocumentRetriever.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        OpenApiToolProvider openApiToolProvider = mock(OpenApiToolProvider.class);

        when(((ChatModel) streamingChatModel).getOptions())
                .thenReturn(OllamaChatOptions.builder().model("qwen3.5:cloud").build());
        when(openApiToolProvider.resolveToolCallbacks(any())).thenReturn(List.of());
        when(ragRetriever.retrieve(any())).thenReturn(List.of());
        when(chatMemory.get(any())).thenReturn(List.of());
        when(streamingChatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chatResponse("ok")));

        SpringAiStreamingPosAssistant assistant = new SpringAiStreamingPosAssistant(
                streamingChatModel,
                () -> "base prompt",
                List.of(new PingTool()),
                ragRetriever,
                ignored -> chatMemory,
                openApiToolProvider,
                null,
                null,
                null);

        assistant
                .chat("user-3::ROLE_TECH", "where is stock", "ctx:role=TECH")
                .collectList()
                .block(Duration.ofSeconds(5));

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(streamingChatModel).stream(promptCaptor.capture());
        assertThat(promptCaptor.getValue().getOptions()).isInstanceOf(OllamaChatOptions.class);
        assertThat(promptCaptor.getValue().getOptions().getModel()).isEqualTo("qwen3.5:cloud");
        assertThat(((OllamaChatOptions) promptCaptor.getValue().getOptions()).getToolCallbacks())
                .hasSize(1);
    }

    private static ChatResponse chatResponse(String token) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(token))));
    }

    static final class PingTool {
        @org.springframework.ai.tool.annotation.Tool(description = "Health check")
        public String ping() {
            return "pong";
        }
    }

    /**
     * The streaming counterpart of the call path's tool-execution guard (#1653).
     *
     * <p>Streaming had no coverage of tool execution at all: the previous mocks implemented only
     * {@link StreamingChatModel}, so they exercised a tool-free fallback rather than the path
     * production runs. `ToolCallingAdvisor.adviseStream` also executes tools on a different
     * scheduler, so this additionally pins that the aggregated text is still correct afterwards.
     */
    @Test
    void chat_executesToolCallsOnTheStreamingPath() {
        StreamingChatModel streamingChatModel =
                mock(StreamingChatModel.class, withSettings().extraInterfaces(ChatModel.class));
        QueryDocumentRetriever ragRetriever = mock(QueryDocumentRetriever.class);
        ChatMemory chatMemory = mock(ChatMemory.class);

        when(((ChatModel) streamingChatModel).getOptions())
                .thenReturn(OllamaChatOptions.builder().model("gpt-oss:120b").build());
        when(ragRetriever.retrieve(any())).thenReturn(List.of());
        when(chatMemory.get(any())).thenReturn(List.of());

        AtomicInteger invocations = new AtomicInteger();
        when(streamingChatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(toolCallResponse("ping")), Flux.just(chatResponse("12 open")));

        SpringAiStreamingPosAssistant assistant = new SpringAiStreamingPosAssistant(
                streamingChatModel,
                () -> "base prompt",
                List.of(new CountingPingTool(invocations)),
                ragRetriever,
                ignored -> chatMemory,
                null,
                null,
                null,
                null);

        List<String> tokens = assistant
                .chat("user-9::ROLE_ADMIN", "how many workorders are open", "ctx")
                .collectList()
                .block();

        assertThat(invocations.get())
                .as("the streamed tool call must actually be executed")
                .isEqualTo(1);
        assertThat(String.join("", tokens == null ? List.of() : tokens)).isEqualTo("12 open");
    }

    /** A streaming-only bean cannot execute tools; construction must fail rather than degrade. */
    @Test
    void constructor_rejectsAStreamingOnlyModelRatherThanSilentlyDroppingToolExecution() {
        StreamingChatModel streamingOnly = mock(StreamingChatModel.class);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SpringAiStreamingPosAssistant(
                        streamingOnly,
                        () -> "base prompt",
                        List.of(),
                        mock(QueryDocumentRetriever.class),
                        ignored -> mock(ChatMemory.class),
                        null,
                        null,
                        null,
                        null))
                .withMessageContaining("must also implement ChatModel");
    }

    private static ChatResponse toolCallResponse(String toolName) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", toolName, "{}")))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    static final class CountingPingTool {
        private final AtomicInteger invocations;

        CountingPingTool(AtomicInteger invocations) {
            this.invocations = invocations;
        }

        @org.springframework.ai.tool.annotation.Tool(description = "Health check")
        public String ping() {
            invocations.incrementAndGet();
            return "pong";
        }
    }
}

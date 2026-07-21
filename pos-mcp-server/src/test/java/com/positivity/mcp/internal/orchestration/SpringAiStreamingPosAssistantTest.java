package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import com.positivity.mcp.internal.service.OpenApiToolProvider;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import reactor.core.publisher.Flux;

class SpringAiStreamingPosAssistantTest {

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ArgumentCaptor<List<Message>> messageListCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }

    @Test
    void chat_usesRagRetrieverAndPersistsMemoryIdOnStreamCompletion() {
        StreamingChatModel streamingChatModel = mock(StreamingChatModel.class);
        QueryDocumentRetriever ragRetriever = mock(QueryDocumentRetriever.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        OpenApiToolProvider openApiToolProvider = mock(OpenApiToolProvider.class);

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
                openApiToolProvider);

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

    private static ChatResponse chatResponse(String token) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(token))));
    }

    static final class PingTool {
        @org.springframework.ai.tool.annotation.Tool(description = "Health check")
        String ping() {
            return "pong";
        }
    }
}
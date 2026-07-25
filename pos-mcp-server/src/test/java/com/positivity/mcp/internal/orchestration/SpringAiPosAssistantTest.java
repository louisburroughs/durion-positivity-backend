package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import com.positivity.mcp.internal.service.OpenApiToolProvider;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.api.OllamaChatOptions;

class SpringAiPosAssistantTest {

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ArgumentCaptor<List<Message>> messageListCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }

    @Test
    void chat_includesRagContextAndPersistsConversationMemory() {
        ChatModel chatModel = mock(ChatModel.class);
        QueryDocumentRetriever ragRetriever = mock(QueryDocumentRetriever.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        OpenApiToolProvider openApiToolProvider = mock(OpenApiToolProvider.class);
        when(chatModel.getDefaultOptions())
                .thenReturn(OllamaChatOptions.builder().model("qwen3.5:cloud").build());
        when(openApiToolProvider.resolveToolCallbacks(any())).thenReturn(List.of());
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("resolved answer"));
        when(ragRetriever.retrieve("where is stock")).thenReturn(List.of(new Document("Inventory policy A")));
        when(chatMemory.get("user-1::ROLE_TECH")).thenReturn(List.of(new AssistantMessage("previous assistant turn")));

        SpringAiPosAssistant assistant = new SpringAiPosAssistant(
                chatModel,
                () -> "base prompt",
                List.of(new PingTool()),
                ragRetriever,
                ignored -> chatMemory,
                openApiToolProvider);

        String response = assistant.chat("user-1::ROLE_TECH", "where is stock", "ctx:role=TECH");

        assertThat(response).isEqualTo("resolved answer");
        verify(ragRetriever).retrieve("where is stock");
        verify(openApiToolProvider).resolveToolCallbacks("where is stock");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        assertThat(promptCaptor.getValue().getOptions()).isNotNull();
        assertThat(promptCaptor.getValue().getOptions().getModel()).isEqualTo("qwen3.5:cloud");
        List<Message> promptMessages = promptCaptor.getValue().getInstructions();
        assertThat(promptMessages).hasSize(3);
        assertThat(promptMessages.getFirst().getText()).isEqualTo("previous assistant turn");
        assertThat(promptMessages.get(1).getText())
                .contains("base prompt")
                .contains("ctx:role=TECH")
                .contains("Relevant retrieved context:")
                .contains("Inventory policy A");
        assertThat(promptMessages.get(2).getText()).isEqualTo("where is stock");

        ArgumentCaptor<List<Message>> persistedMessages = messageListCaptor();
        verify(chatMemory).add(eq("user-1::ROLE_TECH"), persistedMessages.capture());
        assertThat(persistedMessages.getValue()).hasSize(2);
        assertThat(persistedMessages.getValue().getFirst()).isInstanceOf(UserMessage.class);
        assertThat(persistedMessages.getValue().getFirst().getText()).isEqualTo("where is stock");
        assertThat(persistedMessages.getValue().get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(persistedMessages.getValue().get(1).getText()).isEqualTo("resolved answer");
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    static final class PingTool {
        @org.springframework.ai.tool.annotation.Tool(description = "Health check")
        String ping() {
            return "pong";
        }
    }
}

package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import com.positivity.mcp.internal.service.AnswerResolutionLadder;
import com.positivity.mcp.internal.service.AnswerResolutionLadder.LadderResult;
import com.positivity.mcp.internal.service.AnswerResolutionLadder.Rung;
import com.positivity.mcp.internal.service.OpenApiToolProvider;
import java.util.List;
import java.util.Map;
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
        when(chatModel.getOptions())
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
                openApiToolProvider,
                null,
                null);

        String response = assistant.chat("user-1::ROLE_TECH", "where is stock", "ctx:role=TECH");

        assertThat(response).isEqualTo("resolved answer");
        verify(ragRetriever).retrieve("where is stock");
        verify(openApiToolProvider).resolveToolCallbacks("where is stock");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        // Runtime options must be the provider-specific OllamaChatOptions:
        // OllamaChatModel casts the
        // prompt options directly to OllamaChatOptions, so a generic
        // DefaultToolCallingChatOptions
        // would throw ClassCastException at request time.
        assertThat(promptCaptor.getValue().getOptions()).isInstanceOf(OllamaChatOptions.class);
        assertThat(promptCaptor.getValue().getOptions().getModel()).isEqualTo("qwen3.5:cloud");
        assertThat(((OllamaChatOptions) promptCaptor.getValue().getOptions()).getToolCallbacks())
                .hasSize(1);
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

    @Test
    void chat_ragContextInstructsGroundingAndForbidsContradictingInventedFacts() {
        // #1124/#1125: gap-harness alpha showed the model contradicting or ignoring a
        // correctly
        // retrieved glossary.identifiers snippet (invented PO/GL formats, or a flat
        // refusal despite
        // the answer being present). The injected RAG block must explicitly instruct
        // the model to
        // ground its answer in the numbered snippets and refuse to state a fact that
        // isn't supported
        // by them, rather than silently falling back to trained knowledge.
        ChatModel chatModel = mock(ChatModel.class);
        QueryDocumentRetriever ragRetriever = mock(QueryDocumentRetriever.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        OpenApiToolProvider openApiToolProvider = mock(OpenApiToolProvider.class);
        when(chatModel.getOptions())
                .thenReturn(OllamaChatOptions.builder().model("qwen3.5:cloud").build());
        when(openApiToolProvider.resolveToolCallbacks(any())).thenReturn(List.of());
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("resolved answer"));
        when(ragRetriever.retrieve("PO number format"))
                .thenReturn(List.of(new Document("PO numbers are owned by pos-inventory")));
        when(chatMemory.get("user-1::ROLE_TECH")).thenReturn(List.of());

        SpringAiPosAssistant assistant = new SpringAiPosAssistant(
                chatModel,
                () -> "base prompt",
                List.of(new PingTool()),
                ragRetriever,
                ignored -> chatMemory,
                openApiToolProvider,
                null,
                null);

        assistant.chat("user-1::ROLE_TECH", "PO number format", "ctx:role=TECH");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        List<Message> promptMessages = promptCaptor.getValue().getInstructions();
        String systemMessageText = promptMessages.get(promptMessages.size() - 2).getText();
        assertThat(systemMessageText)
                .contains("Relevant retrieved context:")
                .contains("Ground your answer in the numbered snippets")
                .containsIgnoringCase("do not state")
                .containsIgnoringCase("say what you don't know instead of inventing")
                .contains("PO numbers are owned by pos-inventory")
                // The instruction must guard more than identifier/format facts — a fabricated
                // workflow
                // (the core-charge case) slipped through the original identifier-only wording.
                .containsIgnoringCase("workflow")
                .containsIgnoringCase("capability")
                // A documented non-existence must be binding: say it's not modeled, don't
                // invent it,
                // and don't offer to perform an unsupported action.
                .containsIgnoringCase("not modeled")
                .containsIgnoringCase("does not model it")
                .containsIgnoringCase("offer to perform an action")
                // #1124 item 4: the positive obligation must be explicit so the model answers
                // from a
                // covering snippet instead of over-refusing ("I don't have a reference"). It
                // must also
                // forbid deferring to a UI link / another system and constrain answers to
                // snippet names.
                .containsIgnoringCase("answer directly and completely")
                .containsIgnoringCase("do not claim you lack a reference")
                .containsIgnoringCase("do not ask the user for more detail")
                .containsIgnoringCase("UI link")
                .containsIgnoringCase("use only the entities, fields, names, and values");
    }

    @Test
    void chat_handsOffToLadderWhenContentBlank() {
        ChatModel chatModel = mock(ChatModel.class);
        QueryDocumentRetriever ragRetriever = mock(QueryDocumentRetriever.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        AnswerResolutionLadder ladder = mock(AnswerResolutionLadder.class);
        when(chatModel.getOptions())
                .thenReturn(OllamaChatOptions.builder().model("gpt-oss:120b").build());
        when(ragRetriever.retrieve(any())).thenReturn(List.of());
        when(chatMemory.get(any())).thenReturn(List.of());
        // Blank content, answer routed to the thinking channel — the leak scenario.
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponseWithThinking("", "We should call some tool..."));
        when(ladder.resolveFallback("how many workorders are open"))
                .thenReturn(
                        new LadderResult("View them here — Work Orders: /workorders", Rung.DEEP_LINK, "/workorders"));

        SpringAiPosAssistant assistant = new SpringAiPosAssistant(
                chatModel, () -> "base prompt", List.of(), ragRetriever, ignored -> chatMemory, null, ladder, null);

        String response = assistant.chat("user-1::ROLE_ADMIN", "how many workorders are open", "ctx");

        // The reasoning monologue is never surfaced; the ladder result is returned and
        // persisted.
        assertThat(response).isEqualTo("View them here — Work Orders: /workorders");
        ArgumentCaptor<List<Message>> persisted = messageListCaptor();
        verify(chatMemory).add(eq("user-1::ROLE_ADMIN"), persisted.capture());
        assertThat(persisted.getValue().get(1).getText()).isEqualTo("View them here — Work Orders: /workorders");
    }

    @Test
    void chat_returnsDirectContentWithoutConsultingLadder() {
        ChatModel chatModel = mock(ChatModel.class);
        QueryDocumentRetriever ragRetriever = mock(QueryDocumentRetriever.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        AnswerResolutionLadder ladder = mock(AnswerResolutionLadder.class);
        when(chatModel.getOptions())
                .thenReturn(OllamaChatOptions.builder().model("gpt-oss:120b").build());
        when(ragRetriever.retrieve(any())).thenReturn(List.of());
        when(chatMemory.get(any())).thenReturn(List.of());
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("There are 12 open work orders."));

        SpringAiPosAssistant assistant = new SpringAiPosAssistant(
                chatModel, () -> "base prompt", List.of(), ragRetriever, ignored -> chatMemory, null, ladder, null);

        String response = assistant.chat("user-1::ROLE_ADMIN", "how many workorders are open", "ctx");

        assertThat(response).isEqualTo("There are 12 open work orders.");
        verifyNoInteractions(ladder); // a direct answer must never trigger the fallback
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static ChatResponse chatResponseWithThinking(String content, String thinking) {
        AssistantMessage message = AssistantMessage.builder()
                .content(content)
                .properties(Map.of("thinking", thinking))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    static final class PingTool {
        @org.springframework.ai.tool.annotation.Tool(description = "Health check")
        public String ping() {
            return "pong";
        }
    }
}

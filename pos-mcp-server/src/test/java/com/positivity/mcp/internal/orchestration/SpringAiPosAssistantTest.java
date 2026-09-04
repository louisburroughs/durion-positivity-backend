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
import com.positivity.mcp.internal.service.ToolInvocationRecorder;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
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
        ToolInvocationRecorder invocationRecorder = mock(ToolInvocationRecorder.class);
        when(chatModel.getOptions())
                .thenReturn(OllamaChatOptions.builder().model("qwen3.5:cloud").build());
        when(invocationRecorder.wrap(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
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
                invocationRecorder,
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
        verify(invocationRecorder)
                .recordPrompt(
                        org.mockito.ArgumentMatchers.contains("base prompt"),
                        org.mockito.ArgumentMatchers.argThat(definitions -> definitions.size() == 1
                                && definitions.getFirst().name().equals("ping")
                                && definitions.getFirst().description().equals("Health check")
                                && definitions.getFirst().inputSchema().contains("object")));

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
                null,
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
                chatModel,
                () -> "base prompt",
                List.of(),
                ragRetriever,
                ignored -> chatMemory,
                null,
                ladder,
                null,
                null,
                null);

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
                chatModel,
                () -> "base prompt",
                List.of(),
                ragRetriever,
                ignored -> chatMemory,
                null,
                ladder,
                null,
                null,
                null);

        String response = assistant.chat("user-1::ROLE_ADMIN", "how many workorders are open", "ctx");

        assertThat(response).isEqualTo("There are 12 open work orders.");
        verifyNoInteractions(ladder); // a direct answer must never trigger the fallback
    }

    /**
     * Regression guard for the tool-execution path.
     *
     * <p>
     * As of Spring AI 2.0 {@code ChatModel.call} does not run tools: it advertises
     * the tool
     * definitions and returns the model's tool-call turn verbatim, leaving
     * execution to
     * {@code ChatClient}'s {@code ToolCallingAdvisor}. Calling the model directly
     * therefore invoked
     * no tool at all, and because a tool-call turn carries empty content the reply
     * silently degraded
     * to recovered reasoning or a ladder hand-off — indistinguishable, from the
     * outside, from a
     * model that simply chose not to answer. This asserts the tool actually runs
     * and that the
     * post-tool answer is what reaches the caller.
     */
    @Test
    void chat_executesToolCallsAndReturnsThePostToolAnswer() {
        ChatModel chatModel = mock(ChatModel.class);
        QueryDocumentRetriever ragRetriever = mock(QueryDocumentRetriever.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        AnswerResolutionLadder ladder = mock(AnswerResolutionLadder.class);
        when(chatModel.getOptions())
                .thenReturn(OllamaChatOptions.builder().model("gpt-oss:120b").build());
        when(ragRetriever.retrieve(any())).thenReturn(List.of());
        when(chatMemory.get(any())).thenReturn(List.of());

        AtomicInteger invocations = new AtomicInteger();
        // Turn 1: the model asks for the tool and returns no content (the real gpt-oss
        // shape).
        // Turn 2: having seen the tool result, it answers.
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(toolCallResponse("ping"), chatResponse("There are 12 open work orders."));

        SpringAiPosAssistant assistant = new SpringAiPosAssistant(
                chatModel,
                () -> "base prompt",
                List.of(new CountingPingTool(invocations)),
                ragRetriever,
                ignored -> chatMemory,
                null,
                ladder,
                null,
                null,
                null);

        String response = assistant.chat("user-1::ROLE_ADMIN", "how many workorders are open", "ctx");

        assertThat(invocations.get())
                .as("the requested tool must actually be invoked")
                .isEqualTo(1);
        assertThat(response).isEqualTo("There are 12 open work orders.");
        // A tool-call turn has blank content; the ladder must not pre-empt the
        // post-tool answer.
        verifyNoInteractions(ladder);
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

    /**
     * A model naming a tool that is not in the per-request callback list must
     * degrade, not 500.
     *
     * <p>
     * {@code DefaultToolCallingManager} throws a raw {@code IllegalStateException}
     * ("No
     * ToolCallback found for tool name") for an unresolved name, which the session
     * manager would
     * surface as a 500. With sixteen facades in context this is a realistic model
     * slip. It is also
     * the permission gate's backstop: because the client is built with a hand-made
     * {@code ToolCallingManager} that has no bean-name resolver, only callbacks
     * carried in the
     * request's own options are executable, so naming a tool the caller is not
     * entitled to fails
     * closed here rather than executing it.
     */
    @Test
    void chat_degradesWhenTheModelNamesAToolItWasNotGiven() {
        ChatModel chatModel = mock(ChatModel.class);
        QueryDocumentRetriever ragRetriever = mock(QueryDocumentRetriever.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        AnswerResolutionLadder ladder = mock(AnswerResolutionLadder.class);
        when(chatModel.getOptions())
                .thenReturn(OllamaChatOptions.builder().model("gpt-oss:120b").build());
        when(ragRetriever.retrieve(any())).thenReturn(List.of());
        when(chatMemory.get(any())).thenReturn(List.of());
        when(chatModel.call(any(Prompt.class))).thenReturn(toolCallResponse("accounting_listinvoices"));
        when(ladder.resolveFallback(any()))
                .thenReturn(new LadderResult("View them here — Invoices: /invoices", Rung.DEEP_LINK, "/invoices"));

        SpringAiPosAssistant assistant = new SpringAiPosAssistant(
                chatModel,
                () -> "base prompt",
                List.of(new PingTool()),
                ragRetriever,
                ignored -> chatMemory,
                null,
                ladder,
                null,
                null,
                null);

        String response = assistant.chat("user-1::ROLE_ADMIN", "show open invoices", "ctx");

        assertThat(response).isEqualTo("View them here — Invoices: /invoices");
    }

    // ─── ChatClient observability (#1655) ───────────────────────────────────

    /**
     * Collects observation names so a NOOP registry is distinguishable from a live
     * one.
     */
    private static ObservationRegistry recordingRegistry(List<String> sink) {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStart(Observation.Context context) {
                sink.add(context.getName());
            }
        });
        return registry;
    }

    /**
     * The defect: {@code ChatClient.builder(chatModel)} hardcodes
     * {@code ObservationRegistry.NOOP},
     * so the chat-client and per-advisor observations were silently dropped.
     * Model-level metrics
     * survived it — the Ollama bean carries its own registry — which is why the gap
     * was invisible
     * from the metrics that did appear.
     */
    @Test
    void chat_emitsChatClientObservationsIntoTheSuppliedRegistry() {
        ChatModel chatModel = mock(ChatModel.class);
        QueryDocumentRetriever ragRetriever = mock(QueryDocumentRetriever.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatModel.getOptions())
                .thenReturn(OllamaChatOptions.builder().model("m").build());
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("answer"));
        when(ragRetriever.retrieve(any())).thenReturn(List.of());
        when(chatMemory.get(any())).thenReturn(List.of());
        List<String> observed = new ArrayList<>();

        SpringAiPosAssistant assistant = new SpringAiPosAssistant(
                chatModel,
                () -> "base prompt",
                List.of(),
                ragRetriever,
                ignored -> chatMemory,
                null,
                null,
                null,
                null,
                recordingRegistry(observed));

        assistant.chat("user-1::ROLE_ADMIN", "hello", "ctx");

        assertThat(observed).isNotEmpty();
        assertThat(observed).contains("spring.ai.chat.client");
    }

    /** A null registry must degrade to NOOP rather than fail the chat path. */
    @Test
    void chat_withoutARegistry_stillAnswers() {
        ChatModel chatModel = mock(ChatModel.class);
        QueryDocumentRetriever ragRetriever = mock(QueryDocumentRetriever.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatModel.getOptions())
                .thenReturn(OllamaChatOptions.builder().model("m").build());
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("answer"));
        when(ragRetriever.retrieve(any())).thenReturn(List.of());
        when(chatMemory.get(any())).thenReturn(List.of());

        SpringAiPosAssistant assistant = new SpringAiPosAssistant(
                chatModel,
                () -> "base prompt",
                List.of(),
                ragRetriever,
                ignored -> chatMemory,
                null,
                null,
                null,
                null,
                null);

        assertThat(assistant.chat("user-1::ROLE_ADMIN", "hello", "ctx")).isEqualTo("answer");
    }

    /**
     * #1683 follow-up. Spring AI 2.0's {@code OllamaChatModel.buildRequestPrompt}
     * attaches the
     * model's default options ONLY when {@code prompt.getOptions() == null}; a
     * non-null runtime
     * options object is used verbatim, with no merge against the defaults. Every
     * production turn
     * takes the non-null branch ({@code chat} always passes
     * {@code toolCallingOptions(...)}), so
     * the {@code numCtx}/{@code temperature} configured on the chat model reach the
     * wire purely
     * because {@code mutate()} copies them. Nothing else asserts that, and the
     * wire-level tests in
     * {@code OllamaChatModelConfigurationTest} exercise the null-options path only
     * — so without
     * this test, a change that dropped either option from the per-request copy
     * would silently
     * un-fix #1683 with the whole suite still green.
     */
    @Test
    void toolCallingOptions_preservesContextWindowAndTemperatureFromTheModelDefaults() {
        OllamaChatOptions defaults = OllamaChatOptions.builder()
                .model("gpt-oss:120b")
                .temperature(0.0d)
                .numCtx(32768)
                .build();

        ChatOptions perRequest = SpringAiPosAssistant.toolCallingOptions(defaults, List.of());

        assertThat(perRequest).isInstanceOf(OllamaChatOptions.class);
        OllamaChatOptions ollamaPerRequest = (OllamaChatOptions) perRequest;
        assertThat(ollamaPerRequest.getNumCtx()).isEqualTo(32768);
        assertThat(ollamaPerRequest.getTemperature()).isEqualTo(0.0d);
        assertThat(ollamaPerRequest.getModel()).isEqualTo("gpt-oss:120b");
    }

    /**
     * The generic fallback branch drops every provider-specific option — it copies
     * only the model
     * name. That is correct for a non-Ollama {@link ChatOptions} (there is no
     * {@code numCtx} to
     * carry), but it means reaching this branch with an Ollama backend would
     * silently restore the
     * unset-{@code num_ctx} behaviour #1683 fixed. Pinned here so the trade-off is
     * visible rather
     * than discovered.
     */
    @Test
    void toolCallingOptions_genericDefaultsCarryOnlyTheModelName() {
        ChatOptions perRequest = SpringAiPosAssistant.toolCallingOptions(
                ChatOptions.builder().model("some-model").temperature(0.4d).build(), List.of());

        assertThat(perRequest).isNotInstanceOf(OllamaChatOptions.class);
        assertThat(perRequest.getModel()).isEqualTo("some-model");
    }
}

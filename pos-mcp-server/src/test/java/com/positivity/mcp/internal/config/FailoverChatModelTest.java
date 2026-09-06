package com.positivity.mcp.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.mcp.internal.telemetry.FallbackUsage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.web.client.ResourceAccessException;
import reactor.core.publisher.Flux;

/** #1691: a primary failure is answered by the secondary model with the same prompt. */
class FailoverChatModelTest {

    @org.junit.jupiter.api.AfterEach
    void clearFallbackFlag() {
        // A failed assertion before an in-test consume() must not leak TRUE into the next test.
        FallbackUsage.consume();
    }

    /** A model that records the prompts it receives and either answers or fails. */
    static final class ScriptedModel implements ChatModel, StreamingChatModel {
        final List<Prompt> received = new ArrayList<>();
        private final String answer;
        private final RuntimeException failure;
        private final int failAfterElements;

        ScriptedModel(String answer) {
            this(answer, null, -1);
        }

        ScriptedModel(RuntimeException failure) {
            this(null, failure, 0);
        }

        ScriptedModel(String answer, RuntimeException failure, int failAfterElements) {
            this.answer = answer;
            this.failure = failure;
            this.failAfterElements = failAfterElements;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            received.add(prompt);
            if (failure != null) {
                throw failure;
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            received.add(prompt);
            Flux<ChatResponse> tokens = Flux.just("a", "b")
                    .map(token -> new ChatResponse(List.of(new Generation(new AssistantMessage(token)))));
            if (failure == null) {
                return tokens;
            }
            return failAfterElements == 0
                    ? Flux.error(failure)
                    : tokens.take(failAfterElements).concatWith(Flux.error(failure));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder()
                    .model("primary-model")
                    .temperature(0.0)
                    .build();
        }
    }

    private static Prompt prompt() {
        return new Prompt(
                List.of(new UserMessage("question")),
                ToolCallingChatOptions.builder()
                        .model("primary-model")
                        .temperature(0.0)
                        .build());
    }

    @Test
    @DisplayName("a healthy primary answers and the secondary is never called")
    void primaryAnswers_secondaryUntouched() {
        ScriptedModel primary = new ScriptedModel("primary answer");
        ScriptedModel secondary = new ScriptedModel("secondary answer");
        FailoverChatModel model = new FailoverChatModel(primary, secondary, "deepseek-v4-pro:0813");

        ChatResponse response = model.call(prompt());

        assertThat(response.getResult().getOutput().getText()).isEqualTo("primary answer");
        assertThat(secondary.received).isEmpty();
        assertThat(FallbackUsage.consume()).isFalse();
    }

    @Test
    @DisplayName("a failing primary is answered by the secondary with the model name rewritten (#1691)")
    void primaryFails_secondaryAnswersWithItsOwnModelName() {
        ScriptedModel primary = new ScriptedModel(new ResourceAccessException("ollama.com: connection reset"));
        ScriptedModel secondary = new ScriptedModel("secondary answer");
        FailoverChatModel model = new FailoverChatModel(primary, secondary, "deepseek-v4-pro:0813");

        ChatResponse response = model.call(prompt());

        assertThat(response.getResult().getOutput().getText()).isEqualTo("secondary answer");
        assertThat(secondary.received).hasSize(1);
        Prompt forwarded = secondary.received.getFirst();
        assertThat(forwarded.getInstructions()).isEqualTo(prompt().getInstructions());
        assertThat(forwarded.getOptions().getModel()).isEqualTo("deepseek-v4-pro:0813");
        assertThat(forwarded.getOptions().getTemperature()).isEqualTo(0.0);
        assertThat(FallbackUsage.consume()).isTrue();
    }

    @Test
    @DisplayName("a prompt without options goes to the secondary unchanged, so its own default model applies")
    void promptWithoutOptions_isForwardedAsIs() {
        ScriptedModel primary = new ScriptedModel(new IllegalStateException("boom"));
        ScriptedModel secondary = new ScriptedModel("secondary answer");
        Prompt bare = new Prompt(List.of(new UserMessage("question")));

        new FailoverChatModel(primary, secondary, "deepseek-v4-pro:0813").call(bare);

        assertThat(secondary.received.getFirst().getOptions()).isNull();
        FallbackUsage.consume();
    }

    @Test
    @DisplayName("a stream that fails before emitting anything is served by the secondary")
    void streamFailsBeforeFirstToken_secondaryStreams() {
        ScriptedModel primary = new ScriptedModel(new ResourceAccessException("reset"));
        ScriptedModel secondary = new ScriptedModel("ignored");
        FailoverChatModel model = new FailoverChatModel(primary, secondary, "deepseek-v4-pro:0813");

        List<String> tokens = model.stream(prompt())
                .map(response -> response.getResult().getOutput().getText())
                .collectList()
                .block();

        assertThat(tokens).containsExactly("a", "b");
        assertThat(secondary.received.getFirst().getOptions().getModel()).isEqualTo("deepseek-v4-pro:0813");
        // Streams do not set the flag (delivered on another thread); the WARN line is the record.
        assertThat(FallbackUsage.consume()).isFalse();
    }

    @Test
    @DisplayName("a stream that fails after emitting tokens propagates the error rather than appending a second answer")
    void streamFailsMidway_errorPropagates() {
        ScriptedModel primary = new ScriptedModel("x", new ResourceAccessException("reset"), 1);
        ScriptedModel secondary = new ScriptedModel("ignored");
        FailoverChatModel model = new FailoverChatModel(primary, secondary, "deepseek-v4-pro:0813");

        // Reactor rethrows a RuntimeException from block() as itself.
        assertThatThrownBy(() -> model.stream(prompt()).collectList().block())
                .isInstanceOf(ResourceAccessException.class)
                .satisfies(ignored -> assertThat(secondary.received).isEmpty());
        assertThat(FallbackUsage.consume()).isFalse();
    }

    @Test
    @DisplayName("default options are the primary's, so callers keep building per-request options from it")
    void options_areThePrimarys() {
        ScriptedModel primary = new ScriptedModel("p");
        FailoverChatModel model = new FailoverChatModel(primary, new ScriptedModel("s"), "deepseek-v4-pro:0813");

        assertThat(model.getOptions().getModel()).isEqualTo("primary-model");
    }
}

package com.positivity.mcp.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.positivity.mcp.internal.domain.ModelTier;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;

/**
 * Gate 4 (G4.3, #1192): tier→model resolution — configured tier models override
 * the default model's
 * options, unconfigured tiers fall back to the default model, the router tier
 * always runs at
 * temperature 0, and non-mutable default options can never break construction.
 */
class TieredChatModelResolverTest {

    private static final String DEFAULT_MODEL = "gpt-oss:120b";

    private ChatModel defaultChatModel;
    private StreamingChatModel defaultStreamingChatModel;

    @BeforeEach
    void setUp() {
        defaultChatModel = mock(ChatModel.class, withSettings().extraInterfaces(StreamingChatModel.class));
        when(defaultChatModel.getOptions())
                .thenReturn(OllamaChatOptions.builder()
                        .model(DEFAULT_MODEL)
                        .temperature(0.2d)
                        .build());
        defaultStreamingChatModel = (StreamingChatModel) defaultChatModel;
    }

    private TieredChatModelResolver resolver(String router, String simple, String complex) {
        return new TieredChatModelResolver(defaultChatModel, defaultStreamingChatModel, router, simple, complex);
    }

    @Test
    @DisplayName("configured tier model overrides the default options' model name")
    void configuredTierOverridesModelName() {
        TieredChatModelResolver resolver = resolver("qwen3:4b", "qwen3:8b", "qwen3:32b");

        assertThat(resolver.chatModelFor(ModelTier.T2_SIMPLE).getOptions().getModel())
                .isEqualTo("qwen3:8b");
        assertThat(resolver.chatModelFor(ModelTier.T2_COMPLEX).getOptions().getModel())
                .isEqualTo("qwen3:32b");
        // Non-router tiers keep the default temperature.
        assertThat(resolver.chatModelFor(ModelTier.T2_SIMPLE).getOptions().getTemperature())
                .isEqualTo(0.2d);
    }

    @Test
    @DisplayName("unconfigured (blank) tier falls back to the default chat model instance")
    void unconfiguredTierFallsBackToDefaultModel() {
        TieredChatModelResolver resolver = resolver("qwen3:4b", "", " ");

        assertThat(resolver.chatModelFor(ModelTier.T2_SIMPLE)).isSameAs(defaultChatModel);
        assertThat(resolver.chatModelFor(ModelTier.T2_COMPLEX)).isSameAs(defaultChatModel);
        assertThat(resolver.streamingChatModelFor(ModelTier.T2_SIMPLE)).isSameAs(defaultStreamingChatModel);
    }

    @Test
    @DisplayName("router tier always runs at temperature 0, with the configured router model")
    void routerTierIsDeterministic() {
        TieredChatModelResolver resolver = resolver("qwen3:4b", "", "");

        var routerOptions = resolver.chatModelFor(ModelTier.T1_ROUTER).getOptions();
        assertThat(routerOptions.getModel()).isEqualTo("qwen3:4b");
        assertThat(routerOptions.getTemperature()).isEqualTo(0.0d);
    }

    @Test
    @DisplayName("router tier without a configured model still forces temperature 0 on the default model")
    void routerTierWithoutModelStillForcesTemperatureZero() {
        TieredChatModelResolver resolver = resolver("", "", "");

        var routerOptions = resolver.chatModelFor(ModelTier.T1_ROUTER).getOptions();
        assertThat(routerOptions.getModel()).isEqualTo(DEFAULT_MODEL);
        assertThat(routerOptions.getTemperature()).isEqualTo(0.0d);
    }

    @Test
    @DisplayName("an optionless prompt is delegated with the tier options attached (model override reaches Ollama)")
    void optionlessPromptCarriesTierOptions() {
        TieredChatModelResolver resolver = resolver("qwen3:4b", "qwen3:8b", "");
        when(defaultChatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));

        resolver.chatModelFor(ModelTier.T2_SIMPLE).call(new Prompt(new UserMessage("hi")));

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(defaultChatModel).call(promptCaptor.capture());
        assertThat(promptCaptor.getValue().getOptions()).isNotNull();
        assertThat(promptCaptor.getValue().getOptions().getModel()).isEqualTo("qwen3:8b");
    }

    @Test
    @DisplayName("non-mutable default options (test fakes) fall back to the default model — never breaks boot")
    void nonMutableDefaultsFallBackToDefaultModel() {
        ChatModel bareModel = mock(ChatModel.class);
        when(bareModel.getOptions()).thenReturn(null);

        TieredChatModelResolver resolver =
                new TieredChatModelResolver(bareModel, null, "qwen3:4b", "qwen3:8b", "qwen3:32b");

        assertThat(resolver.chatModelFor(ModelTier.T2_SIMPLE)).isSameAs(bareModel);
        assertThat(resolver.chatModelFor(ModelTier.T1_ROUTER)).isSameAs(bareModel);
    }

    @Test
    @DisplayName("modelNameFor: configured name, else default model's name, else the default label")
    void modelNameForResolvesTelemetryLabels() {
        TieredChatModelResolver resolver = resolver("qwen3:4b", "", "");
        assertThat(resolver.modelNameFor(ModelTier.T1_ROUTER)).isEqualTo("qwen3:4b");
        assertThat(resolver.modelNameFor(ModelTier.T2_COMPLEX)).isEqualTo(DEFAULT_MODEL);

        ChatModel bareModel = mock(ChatModel.class);
        when(bareModel.getOptions()).thenReturn(null);
        TieredChatModelResolver bareResolver = new TieredChatModelResolver(bareModel, null, "", "", "");
        assertThat(bareResolver.modelNameFor(ModelTier.T2_COMPLEX)).isEqualTo("default");
    }
}

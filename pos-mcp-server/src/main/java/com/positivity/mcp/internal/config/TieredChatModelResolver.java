package com.positivity.mcp.internal.config;

import com.positivity.mcp.internal.domain.ModelTier;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

/**
 * Gate 4 (G4.3, #1192) tier→model resolver. Maps a {@link ModelTier} to the chat model configured
 * for that tier ({@code mcp.model.router} / {@code mcp.model.simple} / {@code mcp.model.complex})
 * by wrapping the default Ollama chat model with per-tier default options (model name; temperature
 * 0 for the T1 router — the router must be deterministic).
 *
 * <p>Fail-safe by construction: a tier with no configured model name — or a default model whose
 * options cannot be mutated (non-Ollama test fakes) — resolves to the default chat model unchanged,
 * so a missing or invalid tier configuration can never break boot or a request. This is also the
 * rollback story: {@code mcp.model.tiering-enabled=false} bypasses this resolver entirely.
 *
 * <p><strong>Orthogonal to failover:</strong> {@code mcp.model.fallback} is primary→secondary
 * failover <em>within</em> a tier and stays independent of tier routing (Gate 4 drift guard).
 */
public class TieredChatModelResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(TieredChatModelResolver.class);

    /** Telemetry label when no explicit model name is resolvable for a tier. */
    static final String DEFAULT_MODEL_LABEL = "default";

    private static final double ROUTER_TEMPERATURE = 0.0d;

    private final ChatModel defaultChatModel;
    private final @Nullable StreamingChatModel defaultStreamingChatModel;
    private final Map<ModelTier, String> configuredModelNames = new EnumMap<>(ModelTier.class);
    private final Map<ModelTier, ChatModel> chatModelsByTier = new EnumMap<>(ModelTier.class);
    private final Map<ModelTier, StreamingChatModel> streamingModelsByTier = new EnumMap<>(ModelTier.class);

    public TieredChatModelResolver(
            @NonNull ChatModel defaultChatModel,
            @Nullable StreamingChatModel defaultStreamingChatModel,
            @Nullable String routerModelName,
            @Nullable String simpleModelName,
            @Nullable String complexModelName) {
        this.defaultChatModel = defaultChatModel;
        this.defaultStreamingChatModel = defaultStreamingChatModel;
        putConfigured(ModelTier.T1_ROUTER, routerModelName);
        putConfigured(ModelTier.T2_SIMPLE, simpleModelName);
        putConfigured(ModelTier.T2_COMPLEX, complexModelName);
        for (ModelTier tier : ModelTier.values()) {
            chatModelsByTier.put(tier, buildChatModelFor(tier));
            streamingModelsByTier.put(tier, buildStreamingModelFor(tier));
        }
    }

    /** The blocking chat model serving {@code tier}; the default model when the tier is unconfigured. */
    public @NonNull ChatModel chatModelFor(@NonNull ModelTier tier) {
        return chatModelsByTier.get(tier);
    }

    /** The streaming chat model serving {@code tier}; the default model when the tier is unconfigured. */
    public @NonNull StreamingChatModel streamingChatModelFor(@NonNull ModelTier tier) {
        StreamingChatModel model = streamingModelsByTier.get(tier);
        if (model != null) {
            return model;
        }
        throw new IllegalStateException("No streaming chat model available for tier " + tier);
    }

    /**
     * The actual model name serving {@code tier}, for telemetry: the configured tier model, else the
     * default model's configured name, else {@value #DEFAULT_MODEL_LABEL}.
     */
    public @NonNull String modelNameFor(@NonNull ModelTier tier) {
        String configured = configuredModelNames.get(tier);
        if (configured != null) {
            return configured;
        }
        ChatOptions defaults = defaultChatModel.getOptions();
        String model = defaults != null ? defaults.getModel() : null;
        return (model == null || model.isBlank()) ? DEFAULT_MODEL_LABEL : model;
    }

    private void putConfigured(@NonNull ModelTier tier, @Nullable String modelName) {
        if (modelName != null && !modelName.isBlank()) {
            configuredModelNames.put(tier, modelName.trim());
        }
    }

    private @NonNull ChatModel buildChatModelFor(@NonNull ModelTier tier) {
        String modelName = configuredModelNames.get(tier);
        Double temperature = tier == ModelTier.T1_ROUTER ? ROUTER_TEMPERATURE : null;
        if (modelName == null && temperature == null) {
            return defaultChatModel;
        }
        ChatOptions overridden = overrideOptions(defaultChatModel.getOptions(), modelName, temperature);
        if (overridden == null) {
            LOGGER.warn(
                    "MCP tiered model resolver: default chat model options are not mutable; tier={} uses the default model unchanged",
                    tier);
            return defaultChatModel;
        }
        StreamingChatModel streamingDelegate =
                defaultChatModel instanceof StreamingChatModel streaming ? streaming : null;
        return new TierScopedChatModel(defaultChatModel, streamingDelegate, overridden);
    }

    private @Nullable StreamingChatModel buildStreamingModelFor(@NonNull ModelTier tier) {
        if (defaultStreamingChatModel == null) {
            return null;
        }
        String modelName = configuredModelNames.get(tier);
        Double temperature = tier == ModelTier.T1_ROUTER ? ROUTER_TEMPERATURE : null;
        if (modelName == null && temperature == null) {
            return defaultStreamingChatModel;
        }
        ChatOptions defaults = defaultStreamingChatModel instanceof ChatModel chatModel ? chatModel.getOptions() : null;
        ChatOptions overridden = overrideOptions(defaults, modelName, temperature);
        if (overridden == null) {
            LOGGER.warn(
                    "MCP tiered model resolver: default streaming model options are not mutable; tier={} uses the default streaming model unchanged",
                    tier);
            return defaultStreamingChatModel;
        }
        ChatModel callDelegate = defaultStreamingChatModel instanceof ChatModel chatModel ? chatModel : null;
        return new TierScopedChatModel(callDelegate, defaultStreamingChatModel, overridden);
    }

    /**
     * Copies the delegate's default options with the tier's model name (and temperature, for the
     * router) applied. Returns {@code null} when the defaults are not a mutable provider options
     * type — the caller then keeps the default model unchanged (never breaks boot).
     */
    private static @Nullable ChatOptions overrideOptions(
            @Nullable ChatOptions defaults, @Nullable String modelName, @Nullable Double temperature) {
        if (!(defaults instanceof ToolCallingChatOptions toolCallingDefaults)) {
            return null;
        }
        ToolCallingChatOptions.Builder builder = toolCallingDefaults.mutate();
        if (modelName != null) {
            builder.model(modelName);
        }
        if (temperature != null) {
            builder.temperature(temperature);
        }
        return builder.build();
    }

    /**
     * A chat model bound to one tier's options. {@code getOptions()} exposes the tier options
     * so per-request tool-calling options (built by the assistants via {@code mutate()}) carry the
     * tier's model name; a prompt sent without options has the tier options attached before
     * delegation, so direct calls (e.g. the T1 router) also hit the tier model.
     */
    static final class TierScopedChatModel implements ChatModel, StreamingChatModel {

        private final @Nullable ChatModel callDelegate;
        private final @Nullable StreamingChatModel streamDelegate;
        private final ChatOptions tierOptions;

        TierScopedChatModel(
                @Nullable ChatModel callDelegate,
                @Nullable StreamingChatModel streamDelegate,
                @NonNull ChatOptions tierOptions) {
            this.callDelegate = callDelegate;
            this.streamDelegate = streamDelegate;
            this.tierOptions = tierOptions;
        }

        @Override
        public ChatOptions getOptions() {
            return tierOptions;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            if (callDelegate == null) {
                throw new IllegalStateException("No blocking chat model delegate configured for this tier");
            }
            return callDelegate.call(withTierOptions(prompt));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            if (streamDelegate == null) {
                throw new IllegalStateException("No streaming chat model delegate configured for this tier");
            }
            return streamDelegate.stream(withTierOptions(prompt));
        }

        private @NonNull Prompt withTierOptions(@NonNull Prompt prompt) {
            if (prompt.getOptions() != null) {
                return prompt;
            }
            List<Message> instructions = prompt.getInstructions();
            return new Prompt(instructions, tierOptions);
        }
    }
}

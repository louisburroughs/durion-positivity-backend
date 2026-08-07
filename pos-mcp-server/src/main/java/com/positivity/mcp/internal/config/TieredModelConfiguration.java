package com.positivity.mcp.internal.config;

import com.positivity.mcp.internal.domain.ModelTier;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Gate 4 (#1192): wires the tier→model resolver over the default Ollama chat models and exposes the
 * T1 router model as its own bean. Tier model names come from {@code mcp.model.router} /
 * {@code mcp.model.simple} / {@code mcp.model.complex}; a blank name means "use the default model"
 * so an unconfigured tier can never break boot. {@code mcp.model.fallback} (failover) stays
 * orthogonal — see {@link ModelFallbackConfiguration}.
 */
@Configuration
@Profile("alpha")
public class TieredModelConfiguration {

    @Bean
    public @NonNull TieredChatModelResolver tieredChatModelResolver(
            @Qualifier("chatModel") @NonNull ChatModel chatModel,
            @Qualifier("streamingChatModel") @NonNull StreamingChatModel streamingChatModel,
            @Value("${mcp.model.router:}") @NonNull String routerModelName,
            @Value("${mcp.model.simple:}") @NonNull String simpleModelName,
            @Value("${mcp.model.complex:}") @NonNull String complexModelName) {
        return new TieredChatModelResolver(
                chatModel, streamingChatModel, routerModelName, simpleModelName, complexModelName);
    }

    /** The small T1 classifier model (temperature 0), consumed by {@code NltiRouter}. */
    @Bean("routerChatModel")
    public @NonNull ChatModel routerChatModel(@NonNull TieredChatModelResolver tieredChatModelResolver) {
        return tieredChatModelResolver.chatModelFor(ModelTier.T1_ROUTER);
    }
}

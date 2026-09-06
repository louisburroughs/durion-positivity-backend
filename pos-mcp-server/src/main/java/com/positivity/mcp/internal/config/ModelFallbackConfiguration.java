package com.positivity.mcp.internal.config;

import java.time.Duration;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@ConditionalOnProperty(name = "mcp.model.fallback.enabled", havingValue = "true")
public class ModelFallbackConfiguration {

    /**
     * The secondary model shares the primary's temperature and context window (#1683): failover
     * must not silently change determinism or truncate the prompt relative to the primary.
     */
    @Bean("fallbackChatModel")
    public @NonNull ChatModel fallbackChatModel(
            @Value("${OLLAMA_FALLBACK_BASE_URL:${OLLAMA_CHAT_BASE_URL:${OLLAMA_BASE_URL:http://localhost:11434}}}")
                    @NonNull
                    String baseUrl,
            @Value("${mcp.model.fallback.secondary-model-name:deepseek-v4-pro:0813}") @NonNull String modelName,
            @Value("${OLLAMA_API_KEY:}") @NonNull String apiKey,
            @Value("${mcp.model.fallback.timeout:180s}") @NonNull Duration timeout,
            @Value("${spring.ai.ollama.chat.options.temperature:${OLLAMA_CHAT_TEMPERATURE:0.0}}") double temperature,
            @Value("${spring.ai.ollama.chat.options.num-ctx:${OLLAMA_NUM_CTX:32768}}") int numCtx,
            @Qualifier("ollamaChatRetryTemplate") @NonNull RetryTemplate retryTemplate) {
        int timeoutMillis = Math.toIntExact(timeout.toMillis());
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMillis);
        requestFactory.setReadTimeout(timeoutMillis);

        RestClient.Builder restClientBuilder = RestClient.builder().requestFactory(requestFactory);
        if (!apiKey.isBlank()) {
            restClientBuilder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }

        OllamaApi ollamaApi = OllamaApi.builder()
                .baseUrl(baseUrl)
                .restClientBuilder(restClientBuilder)
                .build();

        return OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .options(OllamaChatOptions.builder()
                        .model(modelName)
                        .temperature(temperature)
                        .numCtx(numCtx)
                        .build())
                // The same bounded budget as the primary model (#1749): a failover that hangs on the
                // default template is no failover.
                .retryTemplate(retryTemplate)
                .build();
    }

    /** Bean names of the primary executors wrapped for failover. */
    static final Set<String> WRAPPED_BEANS = Set.of("chatModel", "streamingChatModel");

    /**
     * Wraps the primary {@code chatModel} and {@code streamingChatModel} beans in {@link
     * FailoverChatModel} so every executor — the default and the tier-scoped models the resolver
     * derives from it — fails over to the secondary (#1691). Static so the post-processor is created
     * before the beans it wraps; the secondary is fetched lazily on first wrap because it is defined
     * in this same configuration.
     */
    @Bean
    public static BeanPostProcessor failoverChatModelWrapper(
            @NonNull BeanFactory beanFactory, @NonNull Environment environment) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!WRAPPED_BEANS.contains(beanName) || !(bean instanceof ChatModel primary)) {
                    return bean;
                }
                ChatModel secondary = beanFactory.getBean("fallbackChatModel", ChatModel.class);
                String secondaryModelName =
                        environment.getProperty("mcp.model.fallback.secondary-model-name", "deepseek-v4-pro:0813");
                return new FailoverChatModel(primary, secondary, secondaryModelName);
            }
        };
    }
}

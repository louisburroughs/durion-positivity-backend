package com.positivity.mcp.internal.llm;

import com.positivity.mcp.internal.config.LlmApiProperties;
import com.positivity.mcp.internal.config.LlmRuntimeProperties;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
@ConditionalOnProperty(prefix = "mcp.llm.runtime", name = "provider", havingValue = "ollama")
public class OllamaEmbeddingService implements EmbeddingService {

    private final WebClient.Builder webClientBuilder;
    private final LlmRuntimeProperties runtimeProperties;
    private final LlmApiProperties apiProperties;

    public OllamaEmbeddingService(
            WebClient.Builder webClientBuilder,
            LlmRuntimeProperties runtimeProperties,
            LlmApiProperties apiProperties) {
        this.webClientBuilder = webClientBuilder;
        this.runtimeProperties = runtimeProperties;
        this.apiProperties = apiProperties;
    }

    @Override
    public @NonNull Mono<float[]> embed(@NonNull String text) {
        LlmApiProperties.LlmApi api = resolveApi(runtimeProperties.embeddingApiId());
        WebClient client = webClientBuilder.build();
        return client.post()
                .uri(api.baseUrl())
                .headers(headers -> api.headers().forEach(headers::add))
                .bodyValue(Map.of(
                        "model", api.model(),
                        "prompt", text))
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(runtimeProperties.readTimeout())
                .map(this::toEmbedding);
    }

    private LlmApiProperties.LlmApi resolveApi(String apiId) {
        return apiProperties.apis().stream()
                .filter(api -> api.id().equals(apiId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Unknown embedding API id: " + apiId));
    }

    @SuppressWarnings("unchecked")
    private float[] toEmbedding(Map<String, Object> response) {
        Object embedding = response.get("embedding");
        if (!(embedding instanceof List<?> values)) {
            return new float[0];
        }

        float[] vector = new float[values.size()];
        for (int index = 0; index < values.size(); index++) {
            Object value = values.get(index);
            if (value instanceof Number number) {
                vector[index] = number.floatValue();
            }
        }
        return vector;
    }
}

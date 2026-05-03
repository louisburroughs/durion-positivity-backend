package com.positivity.mcp.internal.config;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.DimensionAwareEmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

@Configuration
@Profile("!test")
public class OllamaEmbeddingModelConfiguration {

    @Bean
    @Primary
    public @NonNull EmbeddingModel embeddingModel(
            RestClient.Builder restClientBuilder,
            @Value("${langchain4j.ollama.embedding-model.base-url:${OLLAMA_EMBEDDING_BASE_URL:http://localhost:11434}}") @NonNull String baseUrl,
            @Value("${langchain4j.ollama.embedding-model.model-name:${OLLAMA_EMBEDDING_MODEL:qwen3-embedding}}") @NonNull String modelName,
            @Value("${mcp.rag.dimension:768}") int dimensions,
            @Value("${langchain4j.ollama.embedding-model.timeout:${OLLAMA_EMBEDDING_TIMEOUT:30s}}") @NonNull Duration timeout,
            @Value("${OLLAMA_API_KEY:}") @NonNull String apiKey) {
        return new HostedOllamaEmbeddingModel(restClientBuilder, baseUrl, modelName, dimensions, timeout, apiKey);
    }

    private static final class HostedOllamaEmbeddingModel extends DimensionAwareEmbeddingModel {

        private static final Logger LOGGER = LoggerFactory.getLogger(HostedOllamaEmbeddingModel.class);

        private final RestClient restClient;
        private final String modelName;
        private final String apiKey;

        private HostedOllamaEmbeddingModel(
                RestClient.Builder restClientBuilder,
                @NonNull String baseUrl,
                @NonNull String modelName,
                int dimensions,
                @NonNull Duration timeout,
                @NonNull String apiKey) {
            RestClient.Builder builder = restClientBuilder.clone().baseUrl(baseUrl);
            if (!apiKey.isBlank()) {
                builder.defaultHeader("Authorization", "Bearer " + apiKey);
            }
            builder.defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            this.restClient = builder.build();
            this.modelName = modelName;
            this.apiKey = apiKey;
            this.dimension = dimensions;
            LOGGER.info(
                    "Configured Ollama embedding model baseUrl={} modelName={} dimensions={} timeout={}",
                    baseUrl,
                    modelName,
                    dimensions,
                    timeout);
        }

        @Override
        public @NonNull Response<List<Embedding>> embedAll(@NonNull List<TextSegment> textSegments) {
            List<String> inputs = textSegments.stream().map(TextSegment::text).toList();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", modelName);
            body.put("input", inputs);
            body.put("dimensions", knownDimension());

            EmbedResponse response = restClient.post()
                    .uri("/api/embed")
                    .headers(headers -> {
                        if (!apiKey.isBlank()) {
                            headers.setBearerAuth(apiKey);
                        }
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(EmbedResponse.class);

            if (response == null || response.embeddings() == null) {
                throw new IllegalStateException("Ollama embedding API returned no embeddings");
            }
            if (response.embeddings().size() != textSegments.size()) {
                throw new IllegalStateException(
                        "Embedding count does not match input count: embeddings=%d inputs=%d"
                                .formatted(response.embeddings().size(), textSegments.size()));
            }

            List<Embedding> embeddings = new ArrayList<>(response.embeddings().size());
            for (List<Float> vector : response.embeddings()) {
                Embedding embedding = Embedding.from(vector);
                if (knownDimension() != null && embedding.dimension() != knownDimension()) {
                    throw new IllegalStateException(
                            "Embedding dimension mismatch: expected=%d actual=%d model=%s"
                                    .formatted(knownDimension(), embedding.dimension(), modelName));
                }
                embeddings.add(embedding);
            }
            return Response.from(embeddings);
        }

        @Override
        public @NonNull String modelName() {
            return modelName;
        }
    }

    private record EmbedResponse(@NonNull String model, @NonNull List<List<Float>> embeddings) {}
}

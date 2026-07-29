package com.positivity.mcp.internal.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@Profile({ "!test", "openapi" })
public class OllamaEmbeddingModelConfiguration {

    @Bean
    @Primary
    public @NonNull EmbeddingModel embeddingModel(
            RestClient.Builder restClientBuilder,
            @Value("${spring.ai.ollama.embedding.base-url:${OLLAMA_EMBEDDING_BASE_URL:${OLLAMA_BASE_URL:http://localhost:11434}}}") @NonNull String baseUrl,
            @Value("${spring.ai.ollama.embedding.options.model:${OLLAMA_EMBEDDING_MODEL:nomic-embed-text}}") @NonNull String modelName,
            @Value("${mcp.rag.dimension:768}") int dimensions,
            @Value("${spring.ai.ollama.embedding.timeout:${OLLAMA_EMBEDDING_TIMEOUT:30s}}") @NonNull Duration timeout,
            @Value("${OLLAMA_API_KEY:}") @NonNull String apiKey) {
        return new HostedOllamaEmbeddingModel(restClientBuilder, baseUrl, modelName, dimensions, timeout, apiKey);
    }

    private static final class HostedOllamaEmbeddingModel extends AbstractEmbeddingModel {

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
            int timeoutMillis = Math.toIntExact(timeout.toMillis());
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(timeoutMillis);
            requestFactory.setReadTimeout(timeoutMillis);

            RestClient.Builder builder = restClientBuilder.clone()
                    .baseUrl(baseUrl)
                    .requestFactory(requestFactory);
            if (!apiKey.isBlank()) {
                builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
            }
            builder.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            this.restClient = builder.build();
            this.modelName = modelName;
            this.apiKey = apiKey;
            this.embeddingDimensions.set(dimensions);
            LOGGER.info(
                    "Configured Ollama embedding model baseUrl={} modelName={} dimensions={} timeout={}",
                    baseUrl,
                    modelName,
                    dimensions,
                    timeout);
        }

        @Override
        public @NonNull EmbeddingResponse call(@NonNull EmbeddingRequest request) {
            List<String> inputs = request.getInstructions().stream()
                    .map(OllamaEmbeddingModelConfiguration::capToContext)
                    .toList();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", modelName);
            body.put("input", inputs);
            body.put("dimensions", dimensions());
            body.put("truncate", true);

            EmbedResponse response = restClient
                    .post()
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
            if (response.embeddings().size() != inputs.size()) {
                throw new IllegalStateException("Embedding count does not match input count: embeddings=%d inputs=%d"
                        .formatted(response.embeddings().size(), inputs.size()));
            }

            List<Embedding> embeddings = new ArrayList<>(response.embeddings().size());
            for (int index = 0; index < response.embeddings().size(); index++) {
                List<Float> vector = response.embeddings().get(index);
                float[] values = new float[vector.size()];
                for (int i = 0; i < vector.size(); i++) {
                    values[i] = vector.get(i);
                }
                Embedding embedding = new Embedding(values, index);
                if (dimensions() > 0 && values.length != dimensions()) {
                    throw new IllegalStateException("Embedding dimension mismatch: expected=%d actual=%d model=%s"
                            .formatted(dimensions(), values.length, modelName));
                }
                embeddings.add(embedding);
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float @NonNull [] embed(@NonNull Document document) {
            return call(new EmbeddingRequest(
                    List.of(getEmbeddingContent(document)),
                    null))
                    .getResult()
                    .getOutput();
        }
    }

    // nomic-embed-text has a ~2048-token context; a long chat message
    // (tool-selection embeds the raw
    // user text) otherwise makes the embed endpoint reject the request with 400
    // "the input length
    // exceeds the context length", surfacing as a 500 on /v1/mcp/chat. Cap
    // conservatively (~4 chars per
    // token) and also ask Ollama to truncate server-side.
    static final int MAX_INPUT_CHARS = 8000;

    static String capToContext(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_INPUT_CHARS ? text : text.substring(0, MAX_INPUT_CHARS);
    }

    private record EmbedResponse(
            @NonNull String model, @NonNull List<List<Float>> embeddings) {
    }
}

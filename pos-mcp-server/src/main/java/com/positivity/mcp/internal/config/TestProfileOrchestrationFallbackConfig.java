package com.positivity.mcp.internal.config;

import com.positivity.mcp.service.AgentOrchestrationService;
import com.positivity.mcp.service.CurrentUserContext;
import com.positivity.mcp.service.DocumentIngestionJob;
import com.positivity.mcp.service.DocumentIngestionJobStatus;
import com.positivity.mcp.service.DocumentIngestionService;
import com.positivity.mcp.service.StreamingAgentOrchestrationService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import reactor.core.publisher.Flux;

@Configuration
@Profile("test")
public class TestProfileOrchestrationFallbackConfig {

    @Bean
    @ConditionalOnMissingBean(AgentOrchestrationService.class)
    AgentOrchestrationService testAgentOrchestrationService() {
        return new AgentOrchestrationService() {
            @Override
            public @NonNull String chat(@NonNull CurrentUserContext currentUserContext, @NonNull String message) {
                return "OpenAPI test-profile fallback response";
            }

            @Override
            public void evict(@NonNull String userId) {
                // No-op fallback for test-profile OpenAPI generation.
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(StreamingAgentOrchestrationService.class)
    StreamingAgentOrchestrationService testStreamingAgentOrchestrationService() {
        return new StreamingAgentOrchestrationService() {
            @Override
            public @NonNull Flux<String> streamChat(
                    @NonNull CurrentUserContext currentUserContext, @NonNull String message) {
                return Flux.just("OpenAPI", "test-profile", "fallback", "stream");
            }

            @Override
            public void evict(@NonNull String userId) {
                // No-op fallback for test-profile OpenAPI generation.
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(DocumentIngestionService.class)
    DocumentIngestionService testDocumentIngestionService() {
        return new DocumentIngestionService() {
            @Override
            public @NonNull DocumentIngestionJob submitDocument(
                    @NonNull String content, @NonNull Map<String, Object> metadata) {
                OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                return new DocumentIngestionJob(
                        UUID.randomUUID(),
                        "openapi-test-document",
                        DocumentIngestionJobStatus.PENDING,
                        now,
                        now,
                        null,
                        null,
                        null,
                        null);
            }

            @Override
            public @NonNull Optional<DocumentIngestionJob> getIngestionJob(@NonNull UUID jobId) {
                return Optional.empty();
            }

            @Override
            public void ingestDocument(@NonNull String content, @NonNull Map<String, Object> metadata) {
                // No-op fallback for test-profile OpenAPI generation.
            }

            @Override
            public void ingestDocuments(
                    @NonNull List<String> contents, @NonNull List<Map<String, Object>> metadataList) {
                // No-op fallback for test-profile OpenAPI generation.
            }
        };
    }
}

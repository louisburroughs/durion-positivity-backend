package com.positivity.mcp.internal.config;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import com.positivity.mcp.service.AgentOrchestrationService;
import com.positivity.mcp.service.DocumentIngestionService;
import com.positivity.mcp.service.StreamingAgentOrchestrationService;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import reactor.core.publisher.Flux;

@Configuration
@Profile("test")
public class SessionAgentManagerTestConfiguration {

    @Bean
    AgentOrchestrationService agentOrchestrationService() {
        AgentOrchestrationService service = mock(AgentOrchestrationService.class);
        when(service.chat(anyString(), anyString(), anyString())).thenReturn("Test assistant response");
        return service;
    }

    @Bean
    StreamingAgentOrchestrationService streamingAgentOrchestrationService() {
        return new StreamingAgentOrchestrationService() {
            @Override
            public @NonNull Flux<String> streamChat(
                    @NonNull String userId, @NonNull String role, @NonNull String message) {
                return Flux.just("test-token");
            }

            @Override
            public void evict(@NonNull String userId) {
                // No-op for test profile stub.
            }
        };
    }

    @Bean
    DocumentIngestionService documentIngestionService() {
        return mock(DocumentIngestionService.class);
    }

    @Bean
    ToolMetadataRepository toolMetadataRepository() {
        return new ToolMetadataRepository() {
            @Override
            public java.util.List<String> findAllRoleNames() {
                return List.of();
            }

            @Override
            public java.util.List<com.positivity.mcp.internal.domain.ToolMetadata> findEnabledByRoleAndWorkflow(
                    String role, String workflowState) {
                return List.of();
            }

            @Override
            public java.util.List<com.positivity.mcp.internal.domain.ToolMetadata> findTopKByEmbeddingForRole(
                    float[] embedding, int limit, String role, String workflowState) {
                return List.of();
            }

            @Override
            public java.util.List<com.positivity.mcp.internal.domain.ToolMetadata> findTopKByEmbedding(
                    float[] embedding, int limit) {
                return List.of();
            }
        };
    }
}

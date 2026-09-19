package com.positivity.mcp.internal.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.domain.ChatOutcome;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import java.util.List;
import java.util.UUID;
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
        when(service.chat(any(CurrentUserContext.class), anyString())).thenReturn("Test assistant response");
        // #2075: Mockito does not run default interface methods, so chatTurn (which the controller
        // now calls instead of chat) must be re-stubbed here or every caller of this bean NPEs.
        when(service.chatTurn(any(CurrentUserContext.class), anyString(), nullable(String.class), nullable(UUID.class)))
                .thenReturn(ChatOutcome.of("Test assistant response"));
        return service;
    }

    @Bean
    StreamingAgentOrchestrationService streamingAgentOrchestrationService() {
        return new StreamingAgentOrchestrationService() {
            @Override
            public @NonNull Flux<String> streamChat(
                    @NonNull CurrentUserContext currentUserContext, @NonNull String message) {
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
            public java.util.List<com.positivity.mcp.internal.domain.ToolMetadata> findEnabledByPermissionsAndWorkflow(
                    java.util.Set<String> permissionCodes, String workflowState) {
                return List.of();
            }

            @Override
            public java.util.List<com.positivity.mcp.internal.domain.ToolMetadata> findEnabledByWorkflow(
                    String workflowState) {
                return List.of();
            }

            @Override
            public java.util.List<com.positivity.mcp.internal.domain.ToolMetadata> findTopKByEmbeddingForPermissions(
                    float[] embedding, int limit, java.util.Set<String> permissionCodes, String workflowState) {
                return List.of();
            }

            @Override
            public java.util.List<com.positivity.mcp.internal.domain.ToolMetadata> findTopKByEmbedding(
                    float[] embedding, int limit) {
                return List.of();
            }

            @Override
            public java.util.List<com.positivity.mcp.internal.domain.DiscoveredOperation>
                    findDiscoveredCandidatesForPermissions(
                            float[] embedding, int limit, java.util.Set<String> permissionCodes, String workflowState) {
                return List.of();
            }

            @Override
            public java.util.UUID upsertDiscoveredOperation(
                    com.positivity.mcp.internal.domain.DiscoveredOperation operation, String domain) {
                return java.util.UUID.fromString("00000000-0000-0000-0000-000000000000");
            }

            @Override
            public int pruneDiscoveredOperationsExcept(
                    java.util.Collection<String> keptNames, java.util.Set<String> excludedDomains) {
                return 0;
            }

            @Override
            public java.util.Set<String> discoveredDomains() {
                return java.util.Set.of();
            }

            @Override
            public void linkToolToWorkflow(java.util.UUID toolId, String workflowState) {
                // no-op stub
            }

            @Override
            public boolean addToolPermission(java.util.UUID toolId, String permissionCode) {
                return false; // no-op stub
            }

            @Override
            public java.util.Optional<java.util.UUID> findDiscoveredToolIdByName(String name) {
                return java.util.Optional.empty();
            }

            @Override
            public java.util.Optional<java.util.UUID> findToolIdByName(String name) {
                return java.util.Optional.empty();
            }

            @Override
            public java.util.Optional<com.positivity.mcp.internal.domain.DiscoveredOperation>
                    findDiscoveredOperationByName(String name) {
                return java.util.Optional.empty();
            }

            @Override
            public java.util.List<String> listToolPermissions(java.util.UUID toolId) {
                return List.of();
            }

            @Override
            public boolean removeToolPermission(java.util.UUID toolId, String permissionCode) {
                return false; // no-op stub
            }
        };
    }
}

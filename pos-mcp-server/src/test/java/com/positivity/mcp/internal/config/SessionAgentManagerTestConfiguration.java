package com.positivity.mcp.internal.config;

import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import com.positivity.mcp.service.AgentOrchestrationService;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
  ToolMetadataRepository toolMetadataRepository() {
    return new ToolMetadataRepository() {
      @Override
      public java.util.List<com.positivity.mcp.internal.domain.ToolMetadata> findEnabledByRoleAndWorkflow(
          String role, String workflowState) {
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

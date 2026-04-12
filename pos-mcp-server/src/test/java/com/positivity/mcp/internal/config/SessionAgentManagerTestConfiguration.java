package com.positivity.mcp.internal.config;

import com.positivity.mcp.service.AgentOrchestrationService;
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
}

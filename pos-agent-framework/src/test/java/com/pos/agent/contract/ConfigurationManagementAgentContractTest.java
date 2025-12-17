package com.pos.agent.contract;

import com.positivity.agent.Agent;
import com.positivity.agent.AgentConsultationRequest;
import com.positivity.agent.AgentGuidanceResponse;
import com.positivity.agent.impl.ConfigurationManagementAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class ConfigurationManagementAgentContractTest {

    private Agent configAgent;

    @BeforeEach
    void setUp() {
        configAgent = new ConfigurationManagementAgent();
    }

    @Test
    void shouldImplementAgentInterface() {
        assertInstanceOf(Agent.class, configAgent);
    }

    @Test
    void shouldReturnValidAgentId() {
        String agentId = configAgent.getAgentId();
        assertNotNull(agentId);
        assertFalse(agentId.trim().isEmpty());
        assertEquals("configuration-management", agentId);
    }

    @Test
    void shouldReturnValidCapabilities() {
        var capabilities = configAgent.getCapabilities();
        assertNotNull(capabilities);
        assertFalse(capabilities.isEmpty());
        assertTrue(capabilities.contains("centralized-config"));
        assertTrue(capabilities.contains("feature-flags"));
        assertTrue(capabilities.contains("secrets-management"));
        assertTrue(capabilities.contains("environment-config"));
    }

    @Test
    void shouldHandleCentralizedConfigRequest() {
        AgentRequest request = AgentRequest.builder()
                .requestType("centralized-config")
                .context("Setup Spring Cloud Config server")
                .build();

        AgentResponse response = configAgent.processRequest(request);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertNotNull(response.getContent());
        assertFalse(response.getContent().trim().isEmpty());
    }

    @Test
    void shouldHandleFeatureFlagsRequest() {
        AgentRequest request = AgentRequest.builder()
                .requestType("feature-flags")
                .context("Implement gradual feature rollout")
                .build();

        AgentResponse response = configAgent.processRequest(request);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertNotNull(response.getContent());
        assertTrue(response.getContent().contains("feature"));
    }

    @Test
    void shouldHandleSecretsManagementRequest() {
        AgentRequest request = AgentRequest.builder()
                .requestType("secrets-management")
                .context("Configure AWS Secrets Manager integration")
                .build();

        AgentResponse response = configAgent.processRequest(request);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertNotNull(response.getContent());
        assertTrue(response.getContent().toLowerCase().contains("secrets"));
    }

    @Test
    void shouldHandleEnvironmentConfigRequest() {
        AgentRequest request = AgentRequest.builder()
                .requestType("environment-config")
                .context("Setup dev, staging, prod configurations")
                .build();

        AgentResponse response = configAgent.processRequest(request);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertNotNull(response.getContent());
        assertTrue(response.getContent().contains("environment"));
    }

    @Test
    void shouldReturnErrorForUnsupportedRequestType() {
        AgentRequest request = AgentRequest.builder()
                .requestType("unsupported-operation")
                .context("This should fail")
                .build();

        AgentResponse response = configAgent.processRequest(request);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertNotNull(response.getErrorMessage());
    }

    @Test
    void shouldHandleNullRequest() {
        AgentResponse response = configAgent.processRequest(null);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertNotNull(response.getErrorMessage());
    }

    @Test
    void shouldReturnConsistentResponseFormat() {
        AgentRequest request = AgentRequest.builder()
                .requestType("centralized-config")
                .context("Test consistency")
                .build();

        AgentResponse response1 = configAgent.processRequest(request);
        AgentResponse response2 = configAgent.processRequest(request);

        assertNotNull(response1);
        assertNotNull(response2);
        assertEquals(response1.isSuccess(), response2.isSuccess());
        assertNotNull(response1.getContent());
        assertNotNull(response2.getContent());
    }
}

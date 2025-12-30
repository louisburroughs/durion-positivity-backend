package com.pos.agent.contract;

import com.pos.agent.core.Agent;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.impl.ConfigurationManagementAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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

    // Agent identity/capabilities are not part of the frozen core contract
    // (REQ-016)
    // Focus on processRequest behavior consistent with core API.

    @Test
    void shouldHandleCentralizedConfigRequest() {
        AgentRequest request = AgentRequest.builder()
                .type("centralized-config")
                .description("Setup Spring Cloud Config server")
                .context("spring-cloud-config")
                .build();

        AgentResponse response = configAgent.processRequest(request);

        assertNotNull(response);
        assertEquals("SUCCESS", response.getStatus());
        assertNotNull(response.getOutput());
        assertFalse(response.getOutput().trim().isEmpty());
    }

    @Test
    void shouldHandleFeatureFlagsRequest() {
        AgentRequest request = AgentRequest.builder()
                .type("feature-flags")
                .description("Implement gradual feature rollout")
                .context("gradual-rollout")
                .build();

        AgentResponse response = configAgent.processRequest(request);

        assertNotNull(response);
        assertEquals("SUCCESS", response.getStatus());
        assertNotNull(response.getOutput());
        assertTrue(response.getOutput().contains("feature"));
    }

    @Test
    void shouldHandleSecretsManagementRequest() {
        AgentRequest request = AgentRequest.builder()
                .type("secrets-management")
                .description("Configure AWS Secrets Manager integration")
                .context("aws-secrets-manager")
                .build();

        AgentResponse response = configAgent.processRequest(request);

        assertNotNull(response);
        assertEquals("SUCCESS", response.getStatus());
        assertNotNull(response.getOutput());
        assertTrue(response.getOutput().toLowerCase().contains("secrets"));
    }

    @Test
    void shouldHandleEnvironmentConfigRequest() {
        AgentRequest request = AgentRequest.builder()
                .type("environment-config")
                .description("Setup dev, staging, prod configurations")
                .context("environments")
                .build();

        AgentResponse response = configAgent.processRequest(request);

        assertNotNull(response);
        assertEquals("SUCCESS", response.getStatus());
        assertNotNull(response.getOutput());
        assertTrue(response.getOutput().contains("environment"));
    }

    @Test
    void shouldReturnErrorForUnsupportedRequestType() {
        AgentRequest request = AgentRequest.builder()
                .type("invalid-operation")
                .description("This should fail")
                .context("invalid")
                .build();

        AgentResponse response = configAgent.processRequest(request);

        assertNotNull(response);
        assertEquals("FAILURE", response.getStatus());
        assertNotNull(response.getOutput());
    }

    @Test
    void shouldHandleNullRequest() {
        AgentResponse response = configAgent.processRequest(null);

        assertNotNull(response);
        assertEquals("FAILURE", response.getStatus());
        assertNotNull(response.getOutput());
    }

    @Test
    void shouldReturnConsistentResponseFormat() {
        AgentRequest request = AgentRequest.builder()
                .type("centralized-config")
                .description("Test consistency")
                .context("spring-cloud-config")
                .build();

        AgentResponse response1 = configAgent.processRequest(request);
        AgentResponse response2 = configAgent.processRequest(request);

        assertNotNull(response1);
        assertNotNull(response2);
        assertEquals(response1.getStatus(), response2.getStatus());
        assertNotNull(response1.getOutput());
        assertNotNull(response2.getOutput());
    }
}

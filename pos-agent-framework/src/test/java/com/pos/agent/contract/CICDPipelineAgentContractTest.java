package com.pos.agent.contract;

import com.pos.agent.core.Agent;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.impl.CICDPipelineAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CICDPipelineAgentContractTest {

    private Agent cicdAgent;

    @BeforeEach
    void setUp() {
        cicdAgent = new CICDPipelineAgent();
    }

    @Test
    void shouldImplementAgentInterface() {
        assertInstanceOf(Agent.class, cicdAgent);
    }

    // Identity/capabilities are outside core Agent contract; validate processing
    // behavior.

    @Test
    void shouldHandleBuildAutomationRequest() {
        AgentRequest request = AgentRequest.builder()
                .type("build-automation")
                .description("Configure Maven build pipeline")
                .context("maven-build")
                .build();

        AgentResponse response = cicdAgent.processRequest(request);

        assertNotNull(response);
        assertEquals("SUCCESS", response.getStatus());
        assertNotNull(response.getOutput());
        assertFalse(response.getOutput().trim().isEmpty());
    }

    @Test
    void shouldHandleTestingPipelineRequest() {
        AgentRequest request = AgentRequest.builder()
                .type("testing-pipeline")
                .description("Setup unit and integration testing")
                .context("unit-integration")
                .build();

        AgentResponse response = cicdAgent.processRequest(request);

        assertNotNull(response);
        assertEquals("SUCCESS", response.getStatus());
        assertNotNull(response.getOutput());
        assertTrue(response.getOutput().contains("testing"));
    }

    @Test
    void shouldHandleDeploymentStrategyRequest() {
        AgentRequest request = AgentRequest.builder()
                .type("deployment-strategy")
                .description("Implement blue-green deployment")
                .context("blue-green")
                .build();

        AgentResponse response = cicdAgent.processRequest(request);

        assertNotNull(response);
        assertEquals("SUCCESS", response.getStatus());
        assertNotNull(response.getOutput());
        assertTrue(response.getOutput().contains("deployment"));
    }

    @Test
    void shouldHandleSecurityScanningRequest() {
        AgentRequest request = AgentRequest.builder()
                .type("security-scanning")
                .description("Configure SAST and DAST scanning")
                .context("sast-dast")
                .build();

        AgentResponse response = cicdAgent.processRequest(request);

        assertNotNull(response);
        assertEquals("SUCCESS", response.getStatus());
        assertNotNull(response.getOutput());
        assertTrue(response.getOutput().toLowerCase().contains("ci/cd"));
    }

    @Test
    void shouldReturnErrorForUnsupportedRequestType() {
        AgentRequest request = AgentRequest.builder()
                .type("invalid-operation")
                .description("This should fail")
                .context("invalid")
                .build();

        AgentResponse response = cicdAgent.processRequest(request);

        assertNotNull(response);
        assertEquals("FAILURE", response.getStatus());
        assertNotNull(response.getOutput());
    }

    @Test
    void shouldHandleNullRequest() {
        AgentResponse response = cicdAgent.processRequest(null);

        assertNotNull(response);
        assertEquals("FAILURE", response.getStatus());
        assertNotNull(response.getOutput());
    }

    @Test
    void shouldReturnConsistentResponseFormat() {
        AgentRequest request = AgentRequest.builder()
                .type("build-automation")
                .description("Test consistency")
                .context("build-test")
                .build();

        AgentResponse response1 = cicdAgent.processRequest(request);
        AgentResponse response2 = cicdAgent.processRequest(request);

        assertNotNull(response1);
        assertNotNull(response2);
        assertEquals(response1.getStatus(), response2.getStatus());
        assertNotNull(response1.getOutput());
        assertNotNull(response2.getOutput());
    }
}

package com.pos.agent.contract;

import com.positivity.agent.Agent;
import com.positivity.agent.AgentConsultationRequest;
import com.positivity.agent.AgentGuidanceResponse;
import com.positivity.agent.impl.CICDPipelineAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
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

    @Test
    void shouldReturnValidAgentId() {
        String agentId = cicdAgent.getAgentId();
        assertNotNull(agentId);
        assertFalse(agentId.trim().isEmpty());
        assertEquals("cicd-pipeline", agentId);
    }

    @Test
    void shouldReturnValidCapabilities() {
        var capabilities = cicdAgent.getCapabilities();
        assertNotNull(capabilities);
        assertFalse(capabilities.isEmpty());
        assertTrue(capabilities.contains("build-automation"));
        assertTrue(capabilities.contains("testing-pipeline"));
        assertTrue(capabilities.contains("deployment-strategy"));
        assertTrue(capabilities.contains("security-scanning"));
    }

    @Test
    void shouldHandleBuildAutomationRequest() {
        AgentRequest request = AgentRequest.builder()
                .requestType("build-automation")
                .context("Configure Maven build pipeline")
                .build();

        AgentResponse response = cicdAgent.processRequest(request);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertNotNull(response.getContent());
        assertFalse(response.getContent().trim().isEmpty());
    }

    @Test
    void shouldHandleTestingPipelineRequest() {
        AgentRequest request = AgentRequest.builder()
                .requestType("testing-pipeline")
                .context("Setup unit and integration testing")
                .build();

        AgentResponse response = cicdAgent.processRequest(request);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertNotNull(response.getContent());
        assertTrue(response.getContent().contains("testing"));
    }

    @Test
    void shouldHandleDeploymentStrategyRequest() {
        AgentRequest request = AgentRequest.builder()
                .requestType("deployment-strategy")
                .context("Implement blue-green deployment")
                .build();

        AgentResponse response = cicdAgent.processRequest(request);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertNotNull(response.getContent());
        assertTrue(response.getContent().contains("deployment"));
    }

    @Test
    void shouldHandleSecurityScanningRequest() {
        AgentRequest request = AgentRequest.builder()
                .requestType("security-scanning")
                .context("Configure SAST and DAST scanning")
                .build();

        AgentResponse response = cicdAgent.processRequest(request);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertNotNull(response.getContent());
        assertTrue(response.getContent().toLowerCase().contains("security"));
    }

    @Test
    void shouldReturnErrorForUnsupportedRequestType() {
        AgentRequest request = AgentRequest.builder()
                .requestType("unsupported-operation")
                .context("This should fail")
                .build();

        AgentResponse response = cicdAgent.processRequest(request);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertNotNull(response.getErrorMessage());
    }

    @Test
    void shouldHandleNullRequest() {
        AgentResponse response = cicdAgent.processRequest(null);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertNotNull(response.getErrorMessage());
    }

    @Test
    void shouldReturnConsistentResponseFormat() {
        AgentRequest request = AgentRequest.builder()
                .requestType("build-automation")
                .context("Test consistency")
                .build();

        AgentResponse response1 = cicdAgent.processRequest(request);
        AgentResponse response2 = cicdAgent.processRequest(request);

        assertNotNull(response1);
        assertNotNull(response2);
        assertEquals(response1.isSuccess(), response2.isSuccess());
        assertNotNull(response1.getContent());
        assertNotNull(response2.getContent());
    }
}

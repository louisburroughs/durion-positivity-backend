package com.pos.agent.impl;

import com.pos.agent.core.AgentManager;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.context.AgentContext;
import com.pos.agent.core.SecurityContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CI/CD pipeline agent guidance using core API.
 * Tests build automation, testing pipelines, security scanning, and deployment
 * strategies.
 * Validates: Requirements REQ-013.1, REQ-013.2, REQ-013.3, REQ-013.4, REQ-013.5
 */
class CICDPipelineAgentTest {

    private final AgentManager agentManager = new AgentManager();
    private final SecurityContext securityContext = SecurityContext.builder()
            .jwtToken("valid-jwt-token-for-tests")
            .userId("cicd-agent-tester")
            .roles(List.of("DEVOPS_ENGINEER", "PIPELINE_ARCHITECT"))
            .permissions(List.of("cicd.configure", "deployment.execute"))
            .serviceId("pos-cicd-agent-tests")
            .serviceType("test")
            .build();

    @Test
    void testBuildAutomationGuidance() {
        AgentContext context = AgentContext.builder()
                .domain("cicd")
                .property("service", "pos-catalog")
                .property("topic", "build-automation")
                .property("query", "How to configure Maven build automation for Spring Boot?")
                .property("keywords", "maven build automation spring boot")
                .build();

        AgentRequest request = AgentRequest.builder()
                .type("cicd")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Test
    void testTestingPipelineGuidance() {
        AgentContext context = AgentContext.builder()
                .domain("cicd")
                .property("service", "pos-order")
                .property("topic", "testing-pipeline")
                .property("query", "How to configure testing pipelines with unit integration contract tests?")
                .property("keywords", "testing pipeline junit mockito")
                .build();

        AgentRequest request = AgentRequest.builder()
                .type("cicd")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Test
    void testSecurityScanningGuidance() {
        AgentContext context = AgentContext.builder()
                .domain("cicd")
                .property("service", "pos-security-service")
                .property("topic", "security-scanning")
                .property("query", "How to integrate security scanning in CI/CD?")
                .property("keywords", "security scanning sast dast dependency")
                .build();

        AgentRequest request = AgentRequest.builder()
                .type("cicd")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Test
    void testJenkinsConfigurationGuidance() {
        AgentContext context = AgentContext.builder()
                .domain("cicd")
                .property("service", "pos-inventory")
                .property("topic", "jenkins")
                .property("query", "How to configure Jenkins pipelines for microservices?")
                .property("keywords", "jenkins pipeline groovy declarative")
                .build();

        AgentRequest request = AgentRequest.builder()
                .type("cicd")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Test
    void testGitHubActionsConfigurationGuidance() {
        AgentContext context = AgentContext.builder()
                .domain("cicd")
                .property("service", "pos-customer")
                .property("topic", "github-actions")
                .property("query", "How to configure GitHub Actions workflows?")
                .property("keywords", "github actions workflow yaml")
                .build();

        AgentRequest request = AgentRequest.builder()
                .type("cicd")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Test
    void testGitLabCIConfigurationGuidance() {
        AgentContext context = AgentContext.builder()
                .domain("cicd")
                .property("service", "pos-price")
                .property("topic", "gitlab-ci")
                .property("query", "How to configure GitLab CI/CD pipelines?")
                .property("keywords", "gitlab ci yaml pipeline")
                .build();

        AgentRequest request = AgentRequest.builder()
                .type("cicd")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Test
    void testBlueGreenDeploymentGuidance() {
        AgentContext context = AgentContext.builder()
                .domain("cicd")
                .property("service", "pos-api-gateway")
                .property("topic", "blue-green")
                .property("query", "How to implement Blue-Green deployment strategy?")
                .property("keywords", "blue green deployment kubernetes")
                .build();

        AgentRequest request = AgentRequest.builder()
                .type("cicd")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Test
    void testCanaryDeploymentGuidance() {
        AgentContext context = AgentContext.builder()
                .domain("cicd")
                .property("service", "pos-order")
                .property("topic", "canary")
                .property("query", "How to implement Canary deployment?")
                .property("keywords", "canary deployment gradual rollout")
                .build();

        AgentRequest request = AgentRequest.builder()
                .type("cicd")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Test
    void testDockerBuildGuidance() {
        AgentContext context = AgentContext.builder()
                .domain("cicd")
                .property("service", "pos-inventory")
                .property("topic", "docker-build")
                .property("query", "How to integrate Docker builds in CI/CD?")
                .property("keywords", "docker build multi stage dockerfile")
                .build();

        AgentRequest request = AgentRequest.builder()
                .type("cicd")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }
}
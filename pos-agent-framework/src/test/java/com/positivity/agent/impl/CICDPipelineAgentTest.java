package com.positivity.agent.impl;

import com.positivity.agent.AgentConsultationRequest;
import com.positivity.agent.AgentGuidanceResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CICDPipelineAgent to verify CI/CD pipeline guidance
 * capabilities
 * Validates: Requirements REQ-013.1, REQ-013.2, REQ-013.3, REQ-013.4, REQ-013.5
 */
class CICDPipelineAgentTest {

    private CICDPipelineAgent cicdAgent;

    @BeforeEach
    void setUp() {
        cicdAgent = new CICDPipelineAgent();
    }

    @Test
    void testBuildAutomationGuidance() throws ExecutionException, InterruptedException {
        // Test build automation configuration guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "cicd",
                "How to configure Maven build automation for Spring Boot with maven build automation spring boot?",
                Map.of("service", "pos-catalog"));

        CompletableFuture<AgentGuidanceResponse> future = cicdAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("Maven Build Automation"));
        assertTrue(response.guidance().contains("spring-boot-maven-plugin"));
        assertTrue(response.guidance().contains("mvn clean package"));
        assertTrue(response.guidance().contains("build-image"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testTestingPipelineGuidance() throws ExecutionException, InterruptedException {
        // Test testing pipeline configuration guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "cicd",
                "How to configure testing pipelines with unit integration contract tests with testing pipeline junit mockito?",
                Map.of("service", "pos-order"));

        CompletableFuture<AgentGuidanceResponse> future = cicdAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("Testing Pipeline Configuration"));
        assertTrue(response.guidance().contains("JUnit 5"));
        assertTrue(response.guidance().contains("Mockito"));
        assertTrue(response.guidance().contains("TestContainers"));
        assertTrue(response.guidance().contains("Pact"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testSecurityScanningGuidance() throws ExecutionException, InterruptedException {
        // Test security scanning integration guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "cicd",
                "How to integrate security scanning in CI/CD with security scanning sast dast dependency?",
                Map.of("service", "pos-security-service"));

        CompletableFuture<AgentGuidanceResponse> future = cicdAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("Security Scanning Integration"));
        assertTrue(response.guidance().contains("SAST"));
        assertTrue(response.guidance().contains("DAST"));
        assertTrue(response.guidance().contains("SonarQube"));
        assertTrue(response.guidance().contains("OWASP Dependency Check"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testJenkinsConfigurationGuidance() throws ExecutionException, InterruptedException {
        // Test Jenkins pipeline configuration guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "cicd",
                "How to configure Jenkins pipelines for microservices with jenkins pipeline groovy declarative?",
                Map.of("service", "pos-inventory"));

        CompletableFuture<AgentGuidanceResponse> future = cicdAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("Jenkins Pipeline Configuration"));
        assertTrue(response.guidance().contains("Jenkinsfile"));
        assertTrue(response.guidance().contains("pipeline {"));
        assertTrue(response.guidance().contains("stages"));
        assertTrue(response.guidance().contains("parallel"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testGitHubActionsConfigurationGuidance() throws ExecutionException, InterruptedException {
        // Test GitHub Actions workflow configuration guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "cicd",
                "How to configure GitHub Actions workflows with github actions workflow yaml?",
                Map.of("service", "pos-customer"));

        CompletableFuture<AgentGuidanceResponse> future = cicdAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("GitHub Actions Configuration"));
        assertTrue(response.guidance().contains(".github/workflows"));
        assertTrue(response.guidance().contains("on: [push, pull_request]"));
        assertTrue(response.guidance().contains("actions/checkout"));
        assertTrue(response.guidance().contains("actions/setup-java"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testGitLabCIConfigurationGuidance() throws ExecutionException, InterruptedException {
        // Test GitLab CI/CD configuration guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "cicd",
                "How to configure GitLab CI/CD pipelines with gitlab ci yaml pipeline?",
                Map.of("service", "pos-price"));

        CompletableFuture<AgentGuidanceResponse> future = cicdAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("GitLab CI/CD Configuration"));
        assertTrue(response.guidance().contains(".gitlab-ci.yml"));
        assertTrue(response.guidance().contains("stages:"));
        assertTrue(response.guidance().contains("script:"));
        assertTrue(response.guidance().contains("artifacts:"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testBlueGreenDeploymentGuidance() throws ExecutionException, InterruptedException {
        // Test Blue-Green deployment strategy guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "cicd",
                "How to implement Blue-Green deployment strategy with blue green deployment kubernetes?",
                Map.of("service", "pos-api-gateway"));

        CompletableFuture<AgentGuidanceResponse> future = cicdAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("Blue-Green Deployment"));
        assertTrue(response.guidance().contains("zero downtime"));
        assertTrue(response.guidance().contains("traffic switching"));
        assertTrue(response.guidance().contains("rollback"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testCanaryDeploymentGuidance() throws ExecutionException, InterruptedException {
        // Test Canary deployment strategy guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "cicd",
                "How to implement Canary deployment with canary deployment gradual rollout?",
                Map.of("service", "pos-order"));

        CompletableFuture<AgentGuidanceResponse> future = cicdAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("Canary Deployment"));
        assertTrue(response.guidance().contains("gradual rollout"));
        assertTrue(response.guidance().contains("traffic percentage"));
        assertTrue(response.guidance().contains("monitoring"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testDockerBuildGuidance() throws ExecutionException, InterruptedException {
        // Test Docker build integration guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "cicd",
                "How to integrate Docker builds in CI/CD with docker build multi stage dockerfile?",
                Map.of("service", "pos-inventory"));

        CompletableFuture<AgentGuidanceResponse> future = cicdAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("Docker Build Integration"));
        assertTrue(response.guidance().contains("multi-stage"));
        assertTrue(response.guidance().contains("Dockerfile"));
        assertTrue(response.guidance().contains("docker build"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testAgentCapabilities() {
        // Verify agent capabilities
        assertTrue(cicdAgent.getCapabilities().contains("build-automation"));
        assertTrue(cicdAgent.getCapabilities().contains("testing-pipelines"));
        assertTrue(cicdAgent.getCapabilities().contains("deployment-strategies"));
        assertTrue(cicdAgent.getCapabilities().contains("security-scanning"));
        assertTrue(cicdAgent.getCapabilities().contains("jenkins"));
        assertTrue(cicdAgent.getCapabilities().contains("github-actions"));
        assertTrue(cicdAgent.getCapabilities().contains("gitlab-ci"));
        assertTrue(cicdAgent.getCapabilities().contains("docker-builds"));
        assertTrue(cicdAgent.getCapabilities().contains("blue-green"));
        assertTrue(cicdAgent.getCapabilities().contains("canary"));
        assertTrue(cicdAgent.getCapabilities().contains("rolling"));
    }

    @Test
    void testCanHandleCICDRequests() {
        // Test that agent can handle CI/CD domain requests
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "cicd",
                "General CI/CD pipeline question with cicd pipeline automation",
                Map.of());

        assertTrue(cicdAgent.canHandle(request));
    }

    @Test
    void testCanHandleCapabilityBasedRequests() {
        // Test that agent can handle requests based on capabilities
        AgentConsultationRequest jenkinsRequest = AgentConsultationRequest.create(
                "deployment",
                "I need Jenkins pipeline guidance with jenkins pipeline configuration",
                Map.of());

        assertTrue(cicdAgent.canHandle(jenkinsRequest));

        AgentConsultationRequest securityRequest = AgentConsultationRequest.create(
                "security",
                "Security scanning integration question with security scanning sast dast",
                Map.of());

        assertTrue(cicdAgent.canHandle(securityRequest));
    }

    @Test
    void testAgentMetadata() {
        // Verify agent metadata
        assertEquals("cicd-pipeline-agent", cicdAgent.getId());
        assertEquals("CI/CD Pipeline Agent", cicdAgent.getName());
        assertEquals("cicd", cicdAgent.getDomain());
        assertTrue(cicdAgent.getDependencies().contains("security-agent"));
        assertTrue(cicdAgent.getDependencies().contains("deployment-agent"));
    }
}
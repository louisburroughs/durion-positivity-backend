package com.pos.agent.context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CICDContext
 * Tests context model for CI/CD pipeline configuration scenarios
 * 
 * Requirements: REQ-013.1 - Build automation guidance
 */
class CICDContextTest {

    private CICDContext context;
    private final String contextId = "cicd-context-123";
    private final String sessionId = "cicd-session-456";

    @BeforeEach
    void setUp() {
        context = new CICDContext(contextId, sessionId);
    }

    @Test
    @DisplayName("Should initialize CICDContext with correct properties")
    void testInitialization() {
        // Assert
        assertEquals(contextId, context.getContextId());
        assertEquals(sessionId, context.getSessionId());
        assertNotNull(context.getCreatedAt());
        assertNotNull(context.getLastUpdated());

        // Verify empty collections
        assertTrue(context.getBuildTools().isEmpty());
        assertTrue(context.getBuildStages().isEmpty());
        assertTrue(context.getBuildConfigurations().isEmpty());
        assertTrue(context.getArtifactRepositories().isEmpty());
        assertTrue(context.getTestingFrameworks().isEmpty());
        assertTrue(context.getTestTypes().isEmpty());
        assertTrue(context.getTestConfigurations().isEmpty());
        assertTrue(context.getCodeQualityTools().isEmpty());
        assertTrue(context.getSecurityScanners().isEmpty());
        assertTrue(context.getVulnerabilityChecks().isEmpty());
        assertTrue(context.getSecurityPolicies().isEmpty());
        assertTrue(context.getComplianceChecks().isEmpty());
        assertTrue(context.getDeploymentStrategies().isEmpty());
        assertTrue(context.getEnvironments().isEmpty());
        assertTrue(context.getDeploymentConfigurations().isEmpty());
        assertTrue(context.getRollbackStrategies().isEmpty());
        assertTrue(context.getOrchestrationTools().isEmpty());
        assertTrue(context.getPipelineConfigurations().isEmpty());
        assertTrue(context.getTriggers().isEmpty());
        assertTrue(context.getNotifications().isEmpty());

        // Verify initial validation state
        assertFalse(context.isValid());
        assertFalse(context.hasSecurityIntegration());
        assertFalse(context.hasTestingAutomation());
        assertFalse(context.hasDeploymentAutomation());
    }

    @Test
    @DisplayName("Should add build tools with configurations")
    void testAddBuildTool() {
        // Arrange
        Map<String, Object> mavenConfig = Map.of(
                "type", "java-build",
                "version", "3.8.6",
                "profiles", "dev,test,prod");

        Instant beforeUpdate = context.getLastUpdated();

        // Act
        context.addBuildTool("maven", mavenConfig);

        // Assert
        assertEquals(1, context.getBuildTools().size());
        assertTrue(context.getBuildTools().contains("maven"));
        assertEquals(mavenConfig, context.getBuildConfigurations().get("maven"));
        assertTrue(context.getLastUpdated().isAfter(beforeUpdate));
        assertTrue(context.isValid());

        // Test duplicate prevention
        context.addBuildTool("maven", Map.of("different", "config"));
        assertEquals(1, context.getBuildTools().size());
        assertEquals(mavenConfig, context.getBuildConfigurations().get("maven"));
    }

    @Test
    @DisplayName("Should add build stages and artifact repositories")
    void testAddBuildStagesAndRepositories() {
        // Act
        context.addBuildStage("compile");
        context.addBuildStage("test");
        context.addBuildStage("package");
        context.addBuildStage("deploy");

        context.addArtifactRepository("nexus");
        context.addArtifactRepository("artifactory");
        context.addArtifactRepository("docker-registry");

        // Assert
        assertEquals(4, context.getBuildStages().size());
        assertTrue(context.getBuildStages().contains("compile"));
        assertTrue(context.getBuildStages().contains("test"));
        assertTrue(context.getBuildStages().contains("package"));
        assertTrue(context.getBuildStages().contains("deploy"));

        assertEquals(3, context.getArtifactRepositories().size());
        assertTrue(context.getArtifactRepositories().contains("nexus"));
        assertTrue(context.getArtifactRepositories().contains("artifactory"));
        assertTrue(context.getArtifactRepositories().contains("docker-registry"));
    }

    @Test
    @DisplayName("Should add testing frameworks and configurations")
    void testAddTestingFrameworks() {
        // Arrange
        Instant beforeUpdate = context.getLastUpdated();

        // Act
        context.addTestingFramework("junit5", "junit-platform-suite");
        context.addTestingFramework("testcontainers", "integration-testing");
        context.addTestType("unit");
        context.addTestType("integration");
        context.addTestType("contract");
        context.addCodeQualityTool("sonarqube");
        context.addCodeQualityTool("checkstyle");

        // Assert
        assertEquals(2, context.getTestingFrameworks().size());
        assertTrue(context.getTestingFrameworks().contains("junit5"));
        assertTrue(context.getTestingFrameworks().contains("testcontainers"));

        assertEquals("junit-platform-suite", context.getTestConfigurations().get("junit5"));
        assertEquals("integration-testing", context.getTestConfigurations().get("testcontainers"));

        assertEquals(3, context.getTestTypes().size());
        assertTrue(context.getTestTypes().contains("unit"));
        assertTrue(context.getTestTypes().contains("integration"));
        assertTrue(context.getTestTypes().contains("contract"));

        assertEquals(2, context.getCodeQualityTools().size());
        assertTrue(context.getCodeQualityTools().contains("sonarqube"));
        assertTrue(context.getCodeQualityTools().contains("checkstyle"));

        assertTrue(context.getLastUpdated().isAfter(beforeUpdate));
        assertTrue(context.hasTestingAutomation());
    }

    @Test
    @DisplayName("Should add security scanning components")
    void testAddSecurityScanning() {
        // Arrange
        Instant beforeUpdate = context.getLastUpdated();

        // Act
        context.addSecurityScanner("sonarqube", "security-policy");
        context.addSecurityScanner("snyk", "vulnerability-policy");
        context.addVulnerabilityCheck("dependency-scan");
        context.addVulnerabilityCheck("container-scan");
        context.addComplianceCheck("owasp-zap");
        context.addComplianceCheck("cis-benchmark");

        // Assert
        assertEquals(2, context.getSecurityScanners().size());
        assertTrue(context.getSecurityScanners().contains("sonarqube"));
        assertTrue(context.getSecurityScanners().contains("snyk"));

        assertEquals("security-policy", context.getSecurityPolicies().get("sonarqube"));
        assertEquals("vulnerability-policy", context.getSecurityPolicies().get("snyk"));

        assertEquals(2, context.getVulnerabilityChecks().size());
        assertTrue(context.getVulnerabilityChecks().contains("dependency-scan"));
        assertTrue(context.getVulnerabilityChecks().contains("container-scan"));

        assertEquals(2, context.getComplianceChecks().size());
        assertTrue(context.getComplianceChecks().contains("owasp-zap"));
        assertTrue(context.getComplianceChecks().contains("cis-benchmark"));

        assertTrue(context.getLastUpdated().isAfter(beforeUpdate));
        assertTrue(context.hasSecurityIntegration());
    }

    @Test
    @DisplayName("Should add deployment strategies and environments")
    void testAddDeploymentStrategies() {
        // Arrange
        Map<String, Object> blueGreenConfig = Map.of(
                "type", "zero-downtime",
                "healthcheck-timeout", "30s",
                "rollback-threshold", "5%");

        Instant beforeUpdate = context.getLastUpdated();

        // Act
        context.addDeploymentStrategy("blue-green", blueGreenConfig);
        context.addDeploymentStrategy("canary", Map.of("type", "gradual-rollout"));
        context.addEnvironment("development");
        context.addEnvironment("staging");
        context.addEnvironment("production");
        context.addRollbackStrategy("automatic");
        context.addRollbackStrategy("manual");

        // Assert
        assertEquals(2, context.getDeploymentStrategies().size());
        assertTrue(context.getDeploymentStrategies().contains("blue-green"));
        assertTrue(context.getDeploymentStrategies().contains("canary"));

        assertEquals(blueGreenConfig, context.getDeploymentConfigurations().get("blue-green"));

        assertEquals(3, context.getEnvironments().size());
        assertTrue(context.getEnvironments().contains("development"));
        assertTrue(context.getEnvironments().contains("staging"));
        assertTrue(context.getEnvironments().contains("production"));

        assertEquals(2, context.getRollbackStrategies().size());
        assertTrue(context.getRollbackStrategies().contains("automatic"));
        assertTrue(context.getRollbackStrategies().contains("manual"));

        assertTrue(context.getLastUpdated().isAfter(beforeUpdate));
        assertTrue(context.hasDeploymentAutomation());
    }

    @Test
    @DisplayName("Should add pipeline orchestration components")
    void testAddPipelineOrchestration() {
        // Arrange
        Map<String, Object> jenkinsConfig = Map.of(
                "type", "ci-server",
                "version", "2.401.3",
                "plugins", "pipeline,docker,kubernetes");

        Instant beforeUpdate = context.getLastUpdated();

        // Act
        context.addOrchestrationTool("jenkins", jenkinsConfig);
        context.addOrchestrationTool("github-actions", Map.of("type", "cloud-ci"));
        context.addTrigger("push-to-main");
        context.addTrigger("pull-request");
        context.addTrigger("scheduled-nightly");
        context.addNotification("slack");
        context.addNotification("email");

        // Assert
        assertEquals(2, context.getOrchestrationTools().size());
        assertTrue(context.getOrchestrationTools().contains("jenkins"));
        assertTrue(context.getOrchestrationTools().contains("github-actions"));

        assertEquals(jenkinsConfig, context.getPipelineConfigurations().get("jenkins"));

        assertEquals(3, context.getTriggers().size());
        assertTrue(context.getTriggers().contains("push-to-main"));
        assertTrue(context.getTriggers().contains("pull-request"));
        assertTrue(context.getTriggers().contains("scheduled-nightly"));

        assertEquals(2, context.getNotifications().size());
        assertTrue(context.getNotifications().contains("slack"));
        assertTrue(context.getNotifications().contains("email"));

        assertTrue(context.getLastUpdated().isAfter(beforeUpdate));
    }

    @Test
    @DisplayName("Should validate context states correctly")
    void testContextValidation() {
        // Initially invalid
        assertFalse(context.isValid());
        assertFalse(context.hasSecurityIntegration());
        assertFalse(context.hasTestingAutomation());
        assertFalse(context.hasDeploymentAutomation());

        // Valid with build tool
        context.addBuildTool("maven", Map.of());
        assertTrue(context.isValid());

        // Valid with testing framework
        CICDContext testContext = new CICDContext("test-ctx", "session");
        testContext.addTestingFramework("junit5", "config");
        assertTrue(testContext.isValid());

        // Valid with deployment strategy
        CICDContext deployContext = new CICDContext("deploy-ctx", "session");
        deployContext.addDeploymentStrategy("blue-green", Map.of());
        assertTrue(deployContext.isValid());

        // Has security integration
        context.addSecurityScanner("sonar", "policy");
        assertTrue(context.hasSecurityIntegration());

        // Has testing automation (needs both frameworks and types)
        context.addTestingFramework("junit5", "config");
        context.addTestType("unit");
        assertTrue(context.hasTestingAutomation());

        // Has deployment automation (needs both strategies and environments)
        context.addDeploymentStrategy("canary", Map.of());
        context.addEnvironment("production");
        assertTrue(context.hasDeploymentAutomation());
    }

    @Test
    @DisplayName("Should prevent duplicate entries")
    void testDuplicatePrevention() {
        // Act - Add same items multiple times
        context.addBuildStage("compile");
        context.addBuildStage("compile");

        context.addTestType("unit");
        context.addTestType("unit");

        context.addEnvironment("production");
        context.addEnvironment("production");

        context.addTrigger("push-to-main");
        context.addTrigger("push-to-main");

        // Assert - Should only have one of each
        assertEquals(1, context.getBuildStages().size());
        assertEquals(1, context.getTestTypes().size());
        assertEquals(1, context.getEnvironments().size());
        assertEquals(1, context.getTriggers().size());
    }

    @Test
    @DisplayName("Should return defensive copies of collections")
    void testDefensiveCopies() {
        // Arrange
        context.addBuildTool("maven", Map.of("type", "java"));
        context.addTestingFramework("junit5", "config");
        context.addSecurityScanner("sonar", "policy");

        // Act - Try to modify returned collections
        context.getBuildTools().add("should-not-be-added");
        context.getTestingFrameworks().add("should-not-be-added");
        context.getSecurityScanners().add("should-not-be-added");
        context.getBuildConfigurations().put("should-not", "be-added");
        context.getTestConfigurations().put("should-not", "be-added");
        context.getSecurityPolicies().put("should-not", "be-added");

        // Assert - Original collections should be unchanged
        assertEquals(1, context.getBuildTools().size());
        assertEquals(1, context.getTestingFrameworks().size());
        assertEquals(1, context.getSecurityScanners().size());
        assertEquals(1, context.getBuildConfigurations().size());
        assertEquals(1, context.getTestConfigurations().size());
        assertEquals(1, context.getSecurityPolicies().size());

        assertTrue(context.getBuildTools().contains("maven"));
        assertFalse(context.getBuildTools().contains("should-not-be-added"));
    }

    @Test
    @DisplayName("Should have meaningful toString representation")
    void testToString() {
        // Arrange
        context.addBuildTool("maven", Map.of());
        context.addTestingFramework("junit5", "config");
        context.addDeploymentStrategy("blue-green", Map.of());

        // Act
        String toString = context.toString();

        // Assert
        assertNotNull(toString);
        assertTrue(toString.contains("CICDContext"));
        assertTrue(toString.contains(contextId));
        assertTrue(toString.contains(sessionId));
        assertTrue(toString.contains("buildTools=1"));
        assertTrue(toString.contains("testFrameworks=1"));
        assertTrue(toString.contains("deployStrategies=1"));
    }

    @Test
    @DisplayName("Should handle complex CI/CD pipeline scenario")
    void testComplexCICDScenario() {
        // Arrange & Act - Build a complex CI/CD context

        // Build automation
        context.addBuildTool("maven", Map.of(
                "type", "java-build",
                "version", "3.8.6",
                "profiles", "dev,test,prod"));
        context.addBuildTool("docker", Map.of(
                "type", "containerization",
                "multi-stage", true));
        context.addBuildStage("compile");
        context.addBuildStage("test");
        context.addBuildStage("package");
        context.addBuildStage("security-scan");
        context.addBuildStage("deploy");
        context.addArtifactRepository("nexus");
        context.addArtifactRepository("docker-registry");

        // Testing pipeline
        context.addTestingFramework("junit5", "junit-platform-suite");
        context.addTestingFramework("testcontainers", "integration-testing");
        context.addTestingFramework("wiremock", "contract-testing");
        context.addTestType("unit");
        context.addTestType("integration");
        context.addTestType("contract");
        context.addTestType("performance");
        context.addCodeQualityTool("sonarqube");
        context.addCodeQualityTool("checkstyle");
        context.addCodeQualityTool("spotbugs");

        // Security scanning
        context.addSecurityScanner("sonarqube", "security-quality-gate");
        context.addSecurityScanner("snyk", "vulnerability-threshold-high");
        context.addSecurityScanner("trivy", "container-security-policy");
        context.addVulnerabilityCheck("dependency-scan");
        context.addVulnerabilityCheck("container-scan");
        context.addVulnerabilityCheck("infrastructure-scan");
        context.addComplianceCheck("owasp-zap");
        context.addComplianceCheck("cis-benchmark");
        context.addComplianceCheck("pci-dss");

        // Deployment strategies
        context.addDeploymentStrategy("blue-green", Map.of(
                "type", "zero-downtime",
                "healthcheck-timeout", "30s",
                "rollback-threshold", "5%"));
        context.addDeploymentStrategy("canary", Map.of(
                "type", "gradual-rollout",
                "traffic-split", "10,25,50,100",
                "success-threshold", "99%"));
        context.addDeploymentStrategy("rolling", Map.of(
                "type", "incremental",
                "batch-size", "25%"));
        context.addEnvironment("development");
        context.addEnvironment("testing");
        context.addEnvironment("staging");
        context.addEnvironment("production");
        context.addRollbackStrategy("automatic");
        context.addRollbackStrategy("manual");
        context.addRollbackStrategy("canary-analysis");

        // Pipeline orchestration
        context.addOrchestrationTool("jenkins", Map.of(
                "type", "ci-server",
                "version", "2.401.3",
                "plugins", "pipeline,docker,kubernetes,sonar"));
        context.addOrchestrationTool("github-actions", Map.of(
                "type", "cloud-ci",
                "runners", "ubuntu-latest,windows-latest"));
        context.addTrigger("push-to-main");
        context.addTrigger("pull-request");
        context.addTrigger("scheduled-nightly");
        context.addTrigger("manual-release");
        context.addNotification("slack");
        context.addNotification("email");
        context.addNotification("teams");

        // Assert - Verify complex scenario
        assertEquals(2, context.getBuildTools().size());
        assertEquals(5, context.getBuildStages().size());
        assertEquals(2, context.getArtifactRepositories().size());
        assertEquals(3, context.getTestingFrameworks().size());
        assertEquals(4, context.getTestTypes().size());
        assertEquals(3, context.getCodeQualityTools().size());
        assertEquals(3, context.getSecurityScanners().size());
        assertEquals(3, context.getVulnerabilityChecks().size());
        assertEquals(3, context.getComplianceChecks().size());
        assertEquals(3, context.getDeploymentStrategies().size());
        assertEquals(4, context.getEnvironments().size());
        assertEquals(3, context.getRollbackStrategies().size());
        assertEquals(2, context.getOrchestrationTools().size());
        assertEquals(4, context.getTriggers().size());
        assertEquals(3, context.getNotifications().size());

        // Verify validation states
        assertTrue(context.isValid());
        assertTrue(context.hasSecurityIntegration());
        assertTrue(context.hasTestingAutomation());
        assertTrue(context.hasDeploymentAutomation());

        // Verify configurations
        Map<String, Object> mavenConfig = (Map<String, Object>) context.getBuildConfigurations().get("maven");
        assertEquals("java-build", mavenConfig.get("type"));
        assertEquals("3.8.6", mavenConfig.get("version"));

        Map<String, Object> canaryConfig = (Map<String, Object>) context.getDeploymentConfigurations().get("canary");
        assertEquals("gradual-rollout", canaryConfig.get("type"));
        assertEquals("10,25,50,100", canaryConfig.get("traffic-split"));

        // Verify security policies
        assertEquals("security-quality-gate", context.getSecurityPolicies().get("sonarqube"));
        assertEquals("vulnerability-threshold-high", context.getSecurityPolicies().get("snyk"));
        assertEquals("container-security-policy", context.getSecurityPolicies().get("trivy"));

        // Verify test configurations
        assertEquals("junit-platform-suite", context.getTestConfigurations().get("junit5"));
        assertEquals("integration-testing", context.getTestConfigurations().get("testcontainers"));
        assertEquals("contract-testing", context.getTestConfigurations().get("wiremock"));
    }
}
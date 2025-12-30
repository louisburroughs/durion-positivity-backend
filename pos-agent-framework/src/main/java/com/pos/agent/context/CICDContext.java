package com.pos.agent.context;

import java.time.Instant;
import java.util.*;

/**
 * Context model for CI/CD pipeline configuration scenarios.
 * Provides comprehensive tracking of build automation, testing,
 * security scanning, and deployment strategies.
 * 
 * Requirements: REQ-013.1 - Build automation guidance
 */
public class CICDContext {
    private final String contextId;
    private final String sessionId;
    private final Instant createdAt;
    private Instant lastUpdated;

    // Build automation
    private final Set<String> buildTools;
    private final Set<String> buildStages;
    private final Map<String, Object> buildConfigurations;
    private final Set<String> artifactRepositories;

    // Testing pipeline
    private final Set<String> testingFrameworks;
    private final Set<String> testTypes;
    private final Map<String, Object> testConfigurations;
    private final Set<String> codeQualityTools;

    // Security scanning
    private final Set<String> securityScanners;
    private final Set<String> vulnerabilityChecks;
    private final Map<String, Object> securityPolicies;
    private final Set<String> complianceChecks;

    // Deployment strategies
    private final Set<String> deploymentStrategies;
    private final Set<String> environments;
    private final Map<String, Object> deploymentConfigurations;
    private final Set<String> rollbackStrategies;

    // Pipeline orchestration
    private final Set<String> orchestrationTools;
    private final Map<String, Object> pipelineConfigurations;
    private final Set<String> triggers;
    private final Set<String> notifications;

    // Additional properties for Kubernetes deployment scenarios
    private String serviceName;
    private String deploymentTarget;
    private String deploymentStrategy;
    private String packagingTool;
    private String environment;

    /**
     * Creates a new CI/CD context with the specified identifiers.
     * 
     * @param contextId Unique identifier for this context
     * @param sessionId Session identifier for tracking
     */
    public CICDContext(String contextId, String sessionId) {
        this.contextId = Objects.requireNonNull(contextId, "Context ID cannot be null");
        this.sessionId = Objects.requireNonNull(sessionId, "Session ID cannot be null");
        this.createdAt = Instant.now();
        this.lastUpdated = Instant.now();

        // Initialize collections
        this.buildTools = new LinkedHashSet<>();
        this.buildStages = new LinkedHashSet<>();
        this.buildConfigurations = new HashMap<>();
        this.artifactRepositories = new LinkedHashSet<>();

        this.testingFrameworks = new LinkedHashSet<>();
        this.testTypes = new LinkedHashSet<>();
        this.testConfigurations = new HashMap<>();
        this.codeQualityTools = new LinkedHashSet<>();

        this.securityScanners = new LinkedHashSet<>();
        this.vulnerabilityChecks = new LinkedHashSet<>();
        this.securityPolicies = new HashMap<>();
        this.complianceChecks = new LinkedHashSet<>();

        this.deploymentStrategies = new LinkedHashSet<>();
        this.environments = new LinkedHashSet<>();
        this.deploymentConfigurations = new HashMap<>();
        this.rollbackStrategies = new LinkedHashSet<>();

        this.orchestrationTools = new LinkedHashSet<>();
        this.pipelineConfigurations = new HashMap<>();
        this.triggers = new LinkedHashSet<>();
        this.notifications = new LinkedHashSet<>();
    }

    /**
     * Creates a new CI/CD context with default identifiers.
     * Used for testing and simple scenarios.
     */
    public CICDContext() {
        this("context-" + System.currentTimeMillis(), "session-" + System.currentTimeMillis());
    }

    // ========== Getters (return defensive copies) ==========

    public String getContextId() {
        return contextId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUpdated() {
        return lastUpdated;
    }

    public Set<String> getBuildTools() {
        return new LinkedHashSet<>(buildTools);
    }

    public Set<String> getBuildStages() {
        return new LinkedHashSet<>(buildStages);
    }

    public Map<String, Object> getBuildConfigurations() {
        return new HashMap<>(buildConfigurations);
    }

    public Set<String> getArtifactRepositories() {
        return new LinkedHashSet<>(artifactRepositories);
    }

    public Set<String> getTestingFrameworks() {
        return new LinkedHashSet<>(testingFrameworks);
    }

    public Set<String> getTestTypes() {
        return new LinkedHashSet<>(testTypes);
    }

    public Map<String, Object> getTestConfigurations() {
        return new HashMap<>(testConfigurations);
    }

    public Set<String> getCodeQualityTools() {
        return new LinkedHashSet<>(codeQualityTools);
    }

    public Set<String> getSecurityScanners() {
        return new LinkedHashSet<>(securityScanners);
    }

    public Set<String> getVulnerabilityChecks() {
        return new LinkedHashSet<>(vulnerabilityChecks);
    }

    public Map<String, Object> getSecurityPolicies() {
        return new HashMap<>(securityPolicies);
    }

    public Set<String> getComplianceChecks() {
        return new LinkedHashSet<>(complianceChecks);
    }

    public Set<String> getDeploymentStrategies() {
        return new LinkedHashSet<>(deploymentStrategies);
    }

    public Set<String> getEnvironments() {
        return new LinkedHashSet<>(environments);
    }

    public Map<String, Object> getDeploymentConfigurations() {
        return new HashMap<>(deploymentConfigurations);
    }

    public Set<String> getRollbackStrategies() {
        return new LinkedHashSet<>(rollbackStrategies);
    }

    public Set<String> getOrchestrationTools() {
        return new LinkedHashSet<>(orchestrationTools);
    }

    public Map<String, Object> getPipelineConfigurations() {
        return new HashMap<>(pipelineConfigurations);
    }

    public Set<String> getTriggers() {
        return new LinkedHashSet<>(triggers);
    }

    public Set<String> getNotifications() {
        return new LinkedHashSet<>(notifications);
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
        updateTimestamp();
    }

    public String getDeploymentTarget() {
        return deploymentTarget;
    }

    public void setDeploymentTarget(String deploymentTarget) {
        this.deploymentTarget = deploymentTarget;
        updateTimestamp();
    }

    public String getDeploymentStrategy() {
        return deploymentStrategy;
    }

    public void setDeploymentStrategy(String deploymentStrategy) {
        this.deploymentStrategy = deploymentStrategy;
        updateTimestamp();
    }

    public String getPackagingTool() {
        return packagingTool;
    }

    public void setPackagingTool(String packagingTool) {
        this.packagingTool = packagingTool;
        updateTimestamp();
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
        updateTimestamp();
    }

    // ========== Build Automation Methods ==========

    /**
     * Adds a build tool with its configuration.
     * Prevents duplicate entries.
     * 
     * @param tool   Tool name (e.g., "maven", "gradle")
     * @param config Tool configuration map
     */
    public void addBuildTool(String tool, Map<String, Object> config) {
        Objects.requireNonNull(tool, "Build tool cannot be null");
        Objects.requireNonNull(config, "Build tool config cannot be null");

        if (!buildTools.contains(tool)) {
            buildTools.add(tool);
            buildConfigurations.put(tool, config);
            updateTimestamp();
        }
    }

    /**
     * Adds a build stage to the pipeline.
     * 
     * @param stage Stage name (e.g., "compile", "test", "package")
     */
    public void addBuildStage(String stage) {
        Objects.requireNonNull(stage, "Build stage cannot be null");
        if (buildStages.add(stage)) {
            updateTimestamp();
        }
    }

    /**
     * Adds an artifact repository.
     * 
     * @param repository Repository name (e.g., "nexus", "artifactory")
     */
    public void addArtifactRepository(String repository) {
        Objects.requireNonNull(repository, "Artifact repository cannot be null");
        if (artifactRepositories.add(repository)) {
            updateTimestamp();
        }
    }

    // ========== Testing Pipeline Methods ==========

    /**
     * Adds a testing framework with its configuration.
     * 
     * @param framework Framework name (e.g., "junit5", "testcontainers")
     * @param config    Framework configuration
     */
    public void addTestingFramework(String framework, Object config) {
        Objects.requireNonNull(framework, "Testing framework cannot be null");
        Objects.requireNonNull(config, "Testing framework config cannot be null");

        if (!testingFrameworks.contains(framework)) {
            testingFrameworks.add(framework);
            testConfigurations.put(framework, config);
            updateTimestamp();
        }
    }

    /**
     * Adds a test type to the pipeline.
     * 
     * @param type Test type (e.g., "unit", "integration", "contract")
     */
    public void addTestType(String type) {
        Objects.requireNonNull(type, "Test type cannot be null");
        if (testTypes.add(type)) {
            updateTimestamp();
        }
    }

    /**
     * Adds a code quality tool.
     * 
     * @param tool Tool name (e.g., "sonarqube", "checkstyle")
     */
    public void addCodeQualityTool(String tool) {
        Objects.requireNonNull(tool, "Code quality tool cannot be null");
        if (codeQualityTools.add(tool)) {
            updateTimestamp();
        }
    }

    // ========== Security Scanning Methods ==========

    /**
     * Adds a security scanner with its policy.
     * 
     * @param scanner Scanner name (e.g., "sonarqube", "snyk")
     * @param policy  Security policy configuration
     */
    public void addSecurityScanner(String scanner, Object policy) {
        Objects.requireNonNull(scanner, "Security scanner cannot be null");
        Objects.requireNonNull(policy, "Security policy cannot be null");

        if (!securityScanners.contains(scanner)) {
            securityScanners.add(scanner);
            securityPolicies.put(scanner, policy);
            updateTimestamp();
        }
    }

    /**
     * Adds a vulnerability check.
     * 
     * @param check Check name (e.g., "dependency-scan", "container-scan")
     */
    public void addVulnerabilityCheck(String check) {
        Objects.requireNonNull(check, "Vulnerability check cannot be null");
        if (vulnerabilityChecks.add(check)) {
            updateTimestamp();
        }
    }

    /**
     * Adds a compliance check.
     * 
     * @param check Check name (e.g., "owasp-zap", "cis-benchmark")
     */
    public void addComplianceCheck(String check) {
        Objects.requireNonNull(check, "Compliance check cannot be null");
        if (complianceChecks.add(check)) {
            updateTimestamp();
        }
    }

    // ========== Deployment Strategy Methods ==========

    /**
     * Adds a deployment strategy with its configuration.
     * 
     * @param strategy Strategy name (e.g., "blue-green", "canary")
     * @param config   Strategy configuration map
     */
    public void addDeploymentStrategy(String strategy, Map<String, Object> config) {
        Objects.requireNonNull(strategy, "Deployment strategy cannot be null");
        Objects.requireNonNull(config, "Deployment strategy config cannot be null");

        if (!deploymentStrategies.contains(strategy)) {
            deploymentStrategies.add(strategy);
            deploymentConfigurations.put(strategy, config);
            updateTimestamp();
        }
    }

    /**
     * Adds a deployment environment.
     * 
     * @param environment Environment name (e.g., "development", "production")
     */
    public void addEnvironment(String environment) {
        Objects.requireNonNull(environment, "Environment cannot be null");
        if (environments.add(environment)) {
            updateTimestamp();
        }
    }

    /**
     * Adds a rollback strategy.
     * 
     * @param strategy Strategy name (e.g., "automatic", "manual")
     */
    public void addRollbackStrategy(String strategy) {
        Objects.requireNonNull(strategy, "Rollback strategy cannot be null");
        if (rollbackStrategies.add(strategy)) {
            updateTimestamp();
        }
    }

    // ========== Pipeline Orchestration Methods ==========

    /**
     * Adds an orchestration tool with its configuration.
     * 
     * @param tool   Tool name (e.g., "jenkins", "github-actions")
     * @param config Tool configuration map
     */
    public void addOrchestrationTool(String tool, Map<String, Object> config) {
        Objects.requireNonNull(tool, "Orchestration tool cannot be null");
        Objects.requireNonNull(config, "Orchestration tool config cannot be null");

        if (!orchestrationTools.contains(tool)) {
            orchestrationTools.add(tool);
            pipelineConfigurations.put(tool, config);
            updateTimestamp();
        }
    }

    /**
     * Adds a pipeline trigger.
     * 
     * @param trigger Trigger name (e.g., "push-to-main", "pull-request")
     */
    public void addTrigger(String trigger) {
        Objects.requireNonNull(trigger, "Trigger cannot be null");
        if (triggers.add(trigger)) {
            updateTimestamp();
        }
    }

    /**
     * Adds a notification channel.
     * 
     * @param notification Channel name (e.g., "slack", "email")
     */
    public void addNotification(String notification) {
        Objects.requireNonNull(notification, "Notification cannot be null");
        if (notifications.add(notification)) {
            updateTimestamp();
        }
    }

    // ========== Validation Methods ==========

    /**
     * Checks if the context has minimum required configuration.
     * 
     * @return true if at least one build tool, testing framework, or deployment
     *         strategy is configured
     */
    public boolean isValid() {
        return !buildTools.isEmpty() ||
                !testingFrameworks.isEmpty() ||
                !deploymentStrategies.isEmpty();
    }

    /**
     * Checks if security scanning is integrated.
     * 
     * @return true if at least one security scanner is configured
     */
    public boolean hasSecurityIntegration() {
        return !securityScanners.isEmpty();
    }

    /**
     * Checks if testing automation is configured.
     * 
     * @return true if both testing frameworks and test types are configured
     */
    public boolean hasTestingAutomation() {
        return !testingFrameworks.isEmpty() && !testTypes.isEmpty();
    }

    /**
     * Checks if deployment automation is configured.
     * 
     * @return true if both deployment strategies and environments are configured
     */
    public boolean hasDeploymentAutomation() {
        return !deploymentStrategies.isEmpty() && !environments.isEmpty();
    }

    // ========== Utility Methods ==========

    /**
     * Updates the last updated timestamp to the current time.
     */
    private void updateTimestamp() {
        this.lastUpdated = Instant.now();
    }

    @Override
    public String toString() {
        return "CICDContext{" +
                "contextId='" + contextId + '\'' +
                ", sessionId='" + sessionId + '\'' +
                ", buildTools=" + buildTools.size() +
                ", testFrameworks=" + testingFrameworks.size() +
                ", securityScanners=" + securityScanners.size() +
                ", deployStrategies=" + deploymentStrategies.size() +
                ", environments=" + environments.size() +
                ", valid=" + isValid() +
                ", createdAt=" + createdAt +
                ", lastUpdated=" + lastUpdated +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        CICDContext that = (CICDContext) o;
        return Objects.equals(contextId, that.contextId) &&
                Objects.equals(sessionId, that.sessionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(contextId, sessionId);
    }
}

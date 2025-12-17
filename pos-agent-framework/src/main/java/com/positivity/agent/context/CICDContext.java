package com.positivity.agent.context;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Context model for CI/CD pipeline configuration scenarios
 * Supports CICDPipelineAgent operations and guidance
 * 
 * Requirements: REQ-013.1 - Build automation guidance
 */
public class CICDContext {

    private final String contextId;
    private final String sessionId;
    private final Instant createdAt;
    private Instant lastUpdated;

    // Build automation components
    private final List<String> buildTools;
    private final List<String> buildStages;
    private final Map<String, Object> buildConfigurations;
    private final List<String> artifactRepositories;

    // Testing pipeline components
    private final List<String> testingFrameworks;
    private final List<String> testTypes;
    private final Map<String, String> testConfigurations;
    private final List<String> codeQualityTools;

    // Security scanning components
    private final List<String> securityScanners;
    private final List<String> vulnerabilityChecks;
    private final Map<String, String> securityPolicies;
    private final List<String> complianceChecks;

    // Deployment strategies
    private final List<String> deploymentStrategies;
    private final List<String> environments;
    private final Map<String, Object> deploymentConfigurations;
    private final List<String> rollbackStrategies;

    // Pipeline orchestration
    private final List<String> orchestrationTools;
    private final Map<String, Object> pipelineConfigurations;
    private final List<String> triggers;
    private final List<String> notifications;

    public CICDContext(String contextId, String sessionId) {
        this.contextId = contextId;
        this.sessionId = sessionId;
        this.createdAt = Instant.now();
        this.lastUpdated = Instant.now();

        this.buildTools = new ArrayList<>();
        this.buildStages = new ArrayList<>();
        this.buildConfigurations = new HashMap<>();
        this.artifactRepositories = new ArrayList<>();

        this.testingFrameworks = new ArrayList<>();
        this.testTypes = new ArrayList<>();
        this.testConfigurations = new HashMap<>();
        this.codeQualityTools = new ArrayList<>();

        this.securityScanners = new ArrayList<>();
        this.vulnerabilityChecks = new ArrayList<>();
        this.securityPolicies = new HashMap<>();
        this.complianceChecks = new ArrayList<>();

        this.deploymentStrategies = new ArrayList<>();
        this.environments = new ArrayList<>();
        this.deploymentConfigurations = new HashMap<>();
        this.rollbackStrategies = new ArrayList<>();

        this.orchestrationTools = new ArrayList<>();
        this.pipelineConfigurations = new HashMap<>();
        this.triggers = new ArrayList<>();
        this.notifications = new ArrayList<>();
    }

    // Build automation management
    public void addBuildTool(String tool, Map<String, Object> configuration) {
        if (!this.buildTools.contains(tool)) {
            this.buildTools.add(tool);
            this.buildConfigurations.put(tool, configuration);
            this.lastUpdated = Instant.now();
        }
    }

    public void addBuildStage(String stage) {
        if (!this.buildStages.contains(stage)) {
            this.buildStages.add(stage);
            this.lastUpdated = Instant.now();
        }
    }

    public void addArtifactRepository(String repository) {
        if (!this.artifactRepositories.contains(repository)) {
            this.artifactRepositories.add(repository);
            this.lastUpdated = Instant.now();
        }
    }

    // Testing pipeline management
    public void addTestingFramework(String framework, String configuration) {
        if (!this.testingFrameworks.contains(framework)) {
            this.testingFrameworks.add(framework);
            this.testConfigurations.put(framework, configuration);
            this.lastUpdated = Instant.now();
        }
    }

    public void addTestType(String testType) {
        if (!this.testTypes.contains(testType)) {
            this.testTypes.add(testType);
            this.lastUpdated = Instant.now();
        }
    }

    public void addCodeQualityTool(String tool) {
        if (!this.codeQualityTools.contains(tool)) {
            this.codeQualityTools.add(tool);
            this.lastUpdated = Instant.now();
        }
    }

    // Security scanning management
    public void addSecurityScanner(String scanner, String policy) {
        if (!this.securityScanners.contains(scanner)) {
            this.securityScanners.add(scanner);
            this.securityPolicies.put(scanner, policy);
            this.lastUpdated = Instant.now();
        }
    }

    public void addVulnerabilityCheck(String check) {
        if (!this.vulnerabilityChecks.contains(check)) {
            this.vulnerabilityChecks.add(check);
            this.lastUpdated = Instant.now();
        }
    }

    public void addComplianceCheck(String check) {
        if (!this.complianceChecks.contains(check)) {
            this.complianceChecks.add(check);
            this.lastUpdated = Instant.now();
        }
    }

    // Deployment strategy management
    public void addDeploymentStrategy(String strategy, Map<String, Object> configuration) {
        if (!this.deploymentStrategies.contains(strategy)) {
            this.deploymentStrategies.add(strategy);
            this.deploymentConfigurations.put(strategy, configuration);
            this.lastUpdated = Instant.now();
        }
    }

    public void addEnvironment(String environment) {
        if (!this.environments.contains(environment)) {
            this.environments.add(environment);
            this.lastUpdated = Instant.now();
        }
    }

    public void addRollbackStrategy(String strategy) {
        if (!this.rollbackStrategies.contains(strategy)) {
            this.rollbackStrategies.add(strategy);
            this.lastUpdated = Instant.now();
        }
    }

    // Pipeline orchestration management
    public void addOrchestrationTool(String tool, Map<String, Object> configuration) {
        if (!this.orchestrationTools.contains(tool)) {
            this.orchestrationTools.add(tool);
            this.pipelineConfigurations.put(tool, configuration);
            this.lastUpdated = Instant.now();
        }
    }

    public void addTrigger(String trigger) {
        if (!this.triggers.contains(trigger)) {
            this.triggers.add(trigger);
            this.lastUpdated = Instant.now();
        }
    }

    public void addNotification(String notification) {
        if (!this.notifications.contains(notification)) {
            this.notifications.add(notification);
            this.lastUpdated = Instant.now();
        }
    }

    // Context validation
    public boolean isValid() {
        return !buildTools.isEmpty() || !testingFrameworks.isEmpty() || !deploymentStrategies.isEmpty();
    }

    public boolean hasSecurityIntegration() {
        return !securityScanners.isEmpty() || !vulnerabilityChecks.isEmpty() || !complianceChecks.isEmpty();
    }

    public boolean hasTestingAutomation() {
        return !testingFrameworks.isEmpty() && !testTypes.isEmpty();
    }

    public boolean hasDeploymentAutomation() {
        return !deploymentStrategies.isEmpty() && !environments.isEmpty();
    }

    // Getters
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

    public List<String> getBuildTools() {
        return new ArrayList<>(buildTools);
    }

    public List<String> getBuildStages() {
        return new ArrayList<>(buildStages);
    }

    public Map<String, Object> getBuildConfigurations() {
        return new HashMap<>(buildConfigurations);
    }

    public List<String> getArtifactRepositories() {
        return new ArrayList<>(artifactRepositories);
    }

    public List<String> getTestingFrameworks() {
        return new ArrayList<>(testingFrameworks);
    }

    public List<String> getTestTypes() {
        return new ArrayList<>(testTypes);
    }

    public Map<String, String> getTestConfigurations() {
        return new HashMap<>(testConfigurations);
    }

    public List<String> getCodeQualityTools() {
        return new ArrayList<>(codeQualityTools);
    }

    public List<String> getSecurityScanners() {
        return new ArrayList<>(securityScanners);
    }

    public List<String> getVulnerabilityChecks() {
        return new ArrayList<>(vulnerabilityChecks);
    }

    public Map<String, String> getSecurityPolicies() {
        return new HashMap<>(securityPolicies);
    }

    public List<String> getComplianceChecks() {
        return new ArrayList<>(complianceChecks);
    }

    public List<String> getDeploymentStrategies() {
        return new ArrayList<>(deploymentStrategies);
    }

    public List<String> getEnvironments() {
        return new ArrayList<>(environments);
    }

    public Map<String, Object> getDeploymentConfigurations() {
        return new HashMap<>(deploymentConfigurations);
    }

    public List<String> getRollbackStrategies() {
        return new ArrayList<>(rollbackStrategies);
    }

    public List<String> getOrchestrationTools() {
        return new ArrayList<>(orchestrationTools);
    }

    public Map<String, Object> getPipelineConfigurations() {
        return new HashMap<>(pipelineConfigurations);
    }

    public List<String> getTriggers() {
        return new ArrayList<>(triggers);
    }

    public List<String> getNotifications() {
        return new ArrayList<>(notifications);
    }

    @Override
    public String toString() {
        return String.format(
                "CICDContext{id='%s', session='%s', buildTools=%d, testFrameworks=%d, deployStrategies=%d}",
                contextId, sessionId, buildTools.size(), testingFrameworks.size(), deploymentStrategies.size());
    }
}
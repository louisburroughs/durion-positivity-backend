package com.pos.agent.context;

import java.time.Instant;
import java.util.*;

/**
 * Context model for resilience engineering scenarios.
 * Tracks service reliability, failure handling, and scaling strategies.
 */
public class ResilienceContext {
    private String contextId;
    private String sessionId;
    private String serviceName;
    private String platform;
    private String failureType;
    private String scalingType;
    private final Instant createdAt;
    private Instant lastUpdated;

    // Specialized collections
    private final Set<String> circuitBreakers;
    private final Map<String, Object> circuitBreakerConfigurations;
    private final Set<String> retryPatterns;
    private final Map<String, Object> retryConfigurations;
    private final Set<String> backoffStrategies;
    private final Set<String> bulkheadPatterns;
    private final Set<String> threadPools;
    private final Set<String> chaosExperiments;
    private final Set<String> healthChecks;
    private final Map<String, Object> sliSloDefinitions;

    public ResilienceContext(String contextId, String sessionId) {
        this.contextId = Objects.requireNonNull(contextId, "Context ID cannot be null");
        this.sessionId = Objects.requireNonNull(sessionId, "Session ID cannot be null");
        this.createdAt = Instant.now();
        this.lastUpdated = Instant.now();
        this.circuitBreakers = new LinkedHashSet<>();
        this.circuitBreakerConfigurations = new HashMap<>();
        this.retryPatterns = new LinkedHashSet<>();
        this.retryConfigurations = new HashMap<>();
        this.backoffStrategies = new LinkedHashSet<>();
        this.bulkheadPatterns = new LinkedHashSet<>();
        this.threadPools = new LinkedHashSet<>();
        this.chaosExperiments = new LinkedHashSet<>();
        this.healthChecks = new LinkedHashSet<>();
        this.sliSloDefinitions = new HashMap<>();
    }

    public String getContextId() {
        return contextId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
        updateTimestamp();
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
        updateTimestamp();
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
        updateTimestamp();
    }

    public String getFailureType() {
        return failureType;
    }

    public void setFailureType(String failureType) {
        this.failureType = failureType;
        updateTimestamp();
    }

    public String getScalingType() {
        return scalingType;
    }

    public void setScalingType(String scalingType) {
        this.scalingType = scalingType;
        updateTimestamp();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUpdated() {
        return lastUpdated;
    }

    public Set<String> getCircuitBreakers() {
        return new LinkedHashSet<>(circuitBreakers);
    }

    public void addCircuitBreaker(String circuitBreaker, Map<String, Object> config) {
        this.circuitBreakers.add(circuitBreaker);
        this.circuitBreakerConfigurations.put(circuitBreaker, config);
        updateTimestamp();
    }

    public Set<String> getRetryPatterns() {
        return new LinkedHashSet<>(retryPatterns);
    }

    public void addRetryPattern(String pattern, Map<String, Object> config) {
        this.retryPatterns.add(pattern);
        this.retryConfigurations.put(pattern, config);
        updateTimestamp();
    }

    public Set<String> getBackoffStrategies() {
        return new LinkedHashSet<>(backoffStrategies);
    }

    public void addBackoffStrategy(String strategy) {
        this.backoffStrategies.add(strategy);
        updateTimestamp();
    }

    public Set<String> getBulkheadPatterns() {
        return new LinkedHashSet<>(bulkheadPatterns);
    }

    public void addBulkheadPattern(String pattern) {
        this.bulkheadPatterns.add(pattern);
        updateTimestamp();
    }

    public Set<String> getThreadPools() {
        return new LinkedHashSet<>(threadPools);
    }

    public void addThreadPool(String pool) {
        this.threadPools.add(pool);
        updateTimestamp();
    }

    public Set<String> getChaosExperiments() {
        return new LinkedHashSet<>(chaosExperiments);
    }

    public void addChaosExperiment(String experiment) {
        this.chaosExperiments.add(experiment);
        updateTimestamp();
    }

    public Set<String> getHealthChecks() {
        return new LinkedHashSet<>(healthChecks);
    }

    public void addHealthCheck(String check) {
        this.healthChecks.add(check);
        updateTimestamp();
    }

    public Map<String, Object> getSliSloDefinitions() {
        return new HashMap<>(sliSloDefinitions);
    }

    public void addSliSloDefinition(String key, Object definition) {
        this.sliSloDefinitions.put(key, definition);
        updateTimestamp();
    }

    private void updateTimestamp() {
        this.lastUpdated = Instant.now();
    }

    @Override
    public String toString() {
        return "ResilienceContext{" +
                "serviceName='" + serviceName + '\'' +
                ", platform='" + platform + '\'' +
                ", failureType='" + failureType + '\'' +
                ", scalingType='" + scalingType + '\'' +
                ", createdAt=" + createdAt +
                ", lastUpdated=" + lastUpdated +
                '}';
    }

    public Map<String, Object> getCircuitBreakerConfigurations() {
        return new HashMap<>(circuitBreakerConfigurations);
    }

    public Map<String, Object> getRetryConfigurations() {
        return new HashMap<>(retryConfigurations);
    }
}

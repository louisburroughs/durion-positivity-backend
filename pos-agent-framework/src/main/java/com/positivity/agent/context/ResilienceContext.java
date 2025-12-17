package com.positivity.agent.context;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Context model for resilience engineering scenarios
 * Supports ResilienceEngineeringAgent operations and guidance
 * 
 * Requirements: REQ-015.1 - Circuit breaker configuration guidance
 */
public class ResilienceContext {

    private final String contextId;
    private final String sessionId;
    private final Instant createdAt;
    private Instant lastUpdated;

    // Circuit breaker patterns
    private final List<String> circuitBreakers;
    private final Map<String, Object> circuitBreakerConfigurations;
    private final Map<String, String> failureThresholds;
    private final Map<String, String> recoveryStrategies;

    // Retry mechanisms
    private final List<String> retryPatterns;
    private final Map<String, Object> retryConfigurations;
    private final List<String> backoffStrategies;
    private final Map<String, String> retryPolicies;

    // Bulkhead patterns
    private final List<String> bulkheadPatterns;
    private final Map<String, Object> resourceIsolation;
    private final List<String> threadPools;
    private final Map<String, String> resourcePartitioning;

    // Chaos engineering
    private final List<String> chaosExperiments;
    private final Map<String, Object> failureInjection;
    private final List<String> resilienceTests;
    private final Map<String, String> chaosTools;

    // Health monitoring and observability
    private final List<String> healthChecks;
    private final Map<String, Object> monitoringConfigurations;
    private final List<String> alertingRules;
    private final Map<String, String> sliSloDefinitions;

    // Timeout and rate limiting
    private final Map<String, String> timeoutConfigurations;
    private final List<String> rateLimiters;
    private final Map<String, Object> rateLimitingPolicies;
    private final List<String> loadSheddingStrategies;

    public ResilienceContext(String contextId, String sessionId) {
        this.contextId = contextId;
        this.sessionId = sessionId;
        this.createdAt = Instant.now();
        this.lastUpdated = Instant.now();

        this.circuitBreakers = new ArrayList<>();
        this.circuitBreakerConfigurations = new HashMap<>();
        this.failureThresholds = new HashMap<>();
        this.recoveryStrategies = new HashMap<>();

        this.retryPatterns = new ArrayList<>();
        this.retryConfigurations = new HashMap<>();
        this.backoffStrategies = new ArrayList<>();
        this.retryPolicies = new HashMap<>();

        this.bulkheadPatterns = new ArrayList<>();
        this.resourceIsolation = new HashMap<>();
        this.threadPools = new ArrayList<>();
        this.resourcePartitioning = new HashMap<>();

        this.chaosExperiments = new ArrayList<>();
        this.failureInjection = new HashMap<>();
        this.resilienceTests = new ArrayList<>();
        this.chaosTools = new HashMap<>();

        this.healthChecks = new ArrayList<>();
        this.monitoringConfigurations = new HashMap<>();
        this.alertingRules = new ArrayList<>();
        this.sliSloDefinitions = new HashMap<>();

        this.timeoutConfigurations = new HashMap<>();
        this.rateLimiters = new ArrayList<>();
        this.rateLimitingPolicies = new HashMap<>();
        this.loadSheddingStrategies = new ArrayList<>();
    }

    // Circuit breaker management
    public void addCircuitBreaker(String breaker, Map<String, Object> configuration) {
        if (!this.circuitBreakers.contains(breaker)) {
            this.circuitBreakers.add(breaker);
            this.circuitBreakerConfigurations.put(breaker, configuration);
            this.lastUpdated = Instant.now();
        }
    }

    public void addFailureThreshold(String service, String threshold) {
        this.failureThresholds.put(service, threshold);
        this.lastUpdated = Instant.now();
    }

    public void addRecoveryStrategy(String service, String strategy) {
        this.recoveryStrategies.put(service, strategy);
        this.lastUpdated = Instant.now();
    }

    // Retry mechanism management
    public void addRetryPattern(String pattern, Map<String, Object> configuration) {
        if (!this.retryPatterns.contains(pattern)) {
            this.retryPatterns.add(pattern);
            this.retryConfigurations.put(pattern, configuration);
            this.lastUpdated = Instant.now();
        }
    }

    public void addBackoffStrategy(String strategy) {
        if (!this.backoffStrategies.contains(strategy)) {
            this.backoffStrategies.add(strategy);
            this.lastUpdated = Instant.now();
        }
    }

    public void addRetryPolicy(String service, String policy) {
        this.retryPolicies.put(service, policy);
        this.lastUpdated = Instant.now();
    }

    // Bulkhead pattern management
    public void addBulkheadPattern(String pattern, Map<String, Object> isolation) {
        if (!this.bulkheadPatterns.contains(pattern)) {
            this.bulkheadPatterns.add(pattern);
            this.resourceIsolation.put(pattern, isolation);
            this.lastUpdated = Instant.now();
        }
    }

    public void addThreadPool(String pool) {
        if (!this.threadPools.contains(pool)) {
            this.threadPools.add(pool);
            this.lastUpdated = Instant.now();
        }
    }

    public void addResourcePartitioning(String resource, String partitioning) {
        this.resourcePartitioning.put(resource, partitioning);
        this.lastUpdated = Instant.now();
    }

    // Chaos engineering management
    public void addChaosExperiment(String experiment, Map<String, Object> injection) {
        if (!this.chaosExperiments.contains(experiment)) {
            this.chaosExperiments.add(experiment);
            this.failureInjection.put(experiment, injection);
            this.lastUpdated = Instant.now();
        }
    }

    public void addResilienceTest(String test) {
        if (!this.resilienceTests.contains(test)) {
            this.resilienceTests.add(test);
            this.lastUpdated = Instant.now();
        }
    }

    public void addChaosTool(String tool, String configuration) {
        this.chaosTools.put(tool, configuration);
        this.lastUpdated = Instant.now();
    }

    // Health monitoring management
    public void addHealthCheck(String check, Map<String, Object> configuration) {
        if (!this.healthChecks.contains(check)) {
            this.healthChecks.add(check);
            this.monitoringConfigurations.put(check, configuration);
            this.lastUpdated = Instant.now();
        }
    }

    public void addAlertingRule(String rule) {
        if (!this.alertingRules.contains(rule)) {
            this.alertingRules.add(rule);
            this.lastUpdated = Instant.now();
        }
    }

    public void addSliSloDefinition(String service, String definition) {
        this.sliSloDefinitions.put(service, definition);
        this.lastUpdated = Instant.now();
    }

    // Timeout and rate limiting management
    public void addTimeoutConfiguration(String service, String timeout) {
        this.timeoutConfigurations.put(service, timeout);
        this.lastUpdated = Instant.now();
    }

    public void addRateLimiter(String limiter, Map<String, Object> policy) {
        if (!this.rateLimiters.contains(limiter)) {
            this.rateLimiters.add(limiter);
            this.rateLimitingPolicies.put(limiter, policy);
            this.lastUpdated = Instant.now();
        }
    }

    public void addLoadSheddingStrategy(String strategy) {
        if (!this.loadSheddingStrategies.contains(strategy)) {
            this.loadSheddingStrategies.add(strategy);
            this.lastUpdated = Instant.now();
        }
    }

    // Context validation
    public boolean isValid() {
        return !circuitBreakers.isEmpty() || !retryPatterns.isEmpty() || !bulkheadPatterns.isEmpty();
    }

    public boolean hasCircuitBreakers() {
        return !circuitBreakers.isEmpty() && !failureThresholds.isEmpty();
    }

    public boolean hasRetryMechanisms() {
        return !retryPatterns.isEmpty() && !backoffStrategies.isEmpty();
    }

    public boolean hasBulkheadPatterns() {
        return !bulkheadPatterns.isEmpty() && !threadPools.isEmpty();
    }

    public boolean hasChaosEngineering() {
        return !chaosExperiments.isEmpty() || !resilienceTests.isEmpty();
    }

    public boolean hasHealthMonitoring() {
        return !healthChecks.isEmpty() && !alertingRules.isEmpty();
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

    public List<String> getCircuitBreakers() {
        return new ArrayList<>(circuitBreakers);
    }

    public Map<String, Object> getCircuitBreakerConfigurations() {
        return new HashMap<>(circuitBreakerConfigurations);
    }

    public Map<String, String> getFailureThresholds() {
        return new HashMap<>(failureThresholds);
    }

    public Map<String, String> getRecoveryStrategies() {
        return new HashMap<>(recoveryStrategies);
    }

    public List<String> getRetryPatterns() {
        return new ArrayList<>(retryPatterns);
    }

    public Map<String, Object> getRetryConfigurations() {
        return new HashMap<>(retryConfigurations);
    }

    public List<String> getBackoffStrategies() {
        return new ArrayList<>(backoffStrategies);
    }

    public Map<String, String> getRetryPolicies() {
        return new HashMap<>(retryPolicies);
    }

    public List<String> getBulkheadPatterns() {
        return new ArrayList<>(bulkheadPatterns);
    }

    public Map<String, Object> getResourceIsolation() {
        return new HashMap<>(resourceIsolation);
    }

    public List<String> getThreadPools() {
        return new ArrayList<>(threadPools);
    }

    public Map<String, String> getResourcePartitioning() {
        return new HashMap<>(resourcePartitioning);
    }

    public List<String> getChaosExperiments() {
        return new ArrayList<>(chaosExperiments);
    }

    public Map<String, Object> getFailureInjection() {
        return new HashMap<>(failureInjection);
    }

    public List<String> getResilienceTests() {
        return new ArrayList<>(resilienceTests);
    }

    public Map<String, String> getChaosTools() {
        return new HashMap<>(chaosTools);
    }

    public List<String> getHealthChecks() {
        return new ArrayList<>(healthChecks);
    }

    public Map<String, Object> getMonitoringConfigurations() {
        return new HashMap<>(monitoringConfigurations);
    }

    public List<String> getAlertingRules() {
        return new ArrayList<>(alertingRules);
    }

    public Map<String, String> getSliSloDefinitions() {
        return new HashMap<>(sliSloDefinitions);
    }

    public Map<String, String> getTimeoutConfigurations() {
        return new HashMap<>(timeoutConfigurations);
    }

    public List<String> getRateLimiters() {
        return new ArrayList<>(rateLimiters);
    }

    public Map<String, Object> getRateLimitingPolicies() {
        return new HashMap<>(rateLimitingPolicies);
    }

    public List<String> getLoadSheddingStrategies() {
        return new ArrayList<>(loadSheddingStrategies);
    }

    @Override
    public String toString() {
        return String.format(
                "ResilienceContext{id='%s', session='%s', circuitBreakers=%d, retryPatterns=%d, bulkheads=%d}",
                contextId, sessionId, circuitBreakers.size(), retryPatterns.size(), bulkheadPatterns.size());
    }
}
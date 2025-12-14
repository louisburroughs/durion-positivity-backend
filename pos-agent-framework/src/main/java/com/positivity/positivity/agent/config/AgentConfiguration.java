package com.positivity.positivity.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;

/**
 * Configuration properties for the agent framework
 */
@Configuration
@ConfigurationProperties(prefix = "positivity.agent")
public class AgentConfiguration {
    
    private Registry registry = new Registry();
    private Performance performance = new Performance();
    private Collaboration collaboration = new Collaboration();
    private Failover failover = new Failover();
    
    public static class Registry {
        private Duration discoveryTimeout = Duration.ofSeconds(1);
        private int maxAgents = 100;
        private boolean enableCaching = true;
        private Duration cacheTimeout = Duration.ofMinutes(5);
        
        // Getters and setters
        public Duration getDiscoveryTimeout() { return discoveryTimeout; }
        public void setDiscoveryTimeout(Duration discoveryTimeout) { this.discoveryTimeout = discoveryTimeout; }
        
        public int getMaxAgents() { return maxAgents; }
        public void setMaxAgents(int maxAgents) { this.maxAgents = maxAgents; }
        
        public boolean isEnableCaching() { return enableCaching; }
        public void setEnableCaching(boolean enableCaching) { this.enableCaching = enableCaching; }
        
        public Duration getCacheTimeout() { return cacheTimeout; }
        public void setCacheTimeout(Duration cacheTimeout) { this.cacheTimeout = cacheTimeout; }
    }
    
    public static class Performance {
        private Duration defaultResponseTimeout = Duration.ofSeconds(3);
        private double defaultAccuracyThreshold = 0.95;
        private double defaultAvailabilityThreshold = 0.999;
        private int defaultMaxConcurrentRequests = 100;
        private boolean enableMetrics = true;
        
        // Getters and setters
        public Duration getDefaultResponseTimeout() { return defaultResponseTimeout; }
        public void setDefaultResponseTimeout(Duration defaultResponseTimeout) { this.defaultResponseTimeout = defaultResponseTimeout; }
        
        public double getDefaultAccuracyThreshold() { return defaultAccuracyThreshold; }
        public void setDefaultAccuracyThreshold(double defaultAccuracyThreshold) { this.defaultAccuracyThreshold = defaultAccuracyThreshold; }
        
        public double getDefaultAvailabilityThreshold() { return defaultAvailabilityThreshold; }
        public void setDefaultAvailabilityThreshold(double defaultAvailabilityThreshold) { this.defaultAvailabilityThreshold = defaultAvailabilityThreshold; }
        
        public int getDefaultMaxConcurrentRequests() { return defaultMaxConcurrentRequests; }
        public void setDefaultMaxConcurrentRequests(int defaultMaxConcurrentRequests) { this.defaultMaxConcurrentRequests = defaultMaxConcurrentRequests; }
        
        public boolean isEnableMetrics() { return enableMetrics; }
        public void setEnableMetrics(boolean enableMetrics) { this.enableMetrics = enableMetrics; }
    }
    
    public static class Collaboration {
        private Duration consistencyValidationTimeout = Duration.ofSeconds(3);
        private double consistencyThreshold = 0.8;
        private int maxParticipatingAgents = 5;
        private boolean enableConflictResolution = true;
        
        // Getters and setters
        public Duration getConsistencyValidationTimeout() { return consistencyValidationTimeout; }
        public void setConsistencyValidationTimeout(Duration consistencyValidationTimeout) { this.consistencyValidationTimeout = consistencyValidationTimeout; }
        
        public double getConsistencyThreshold() { return consistencyThreshold; }
        public void setConsistencyThreshold(double consistencyThreshold) { this.consistencyThreshold = consistencyThreshold; }
        
        public int getMaxParticipatingAgents() { return maxParticipatingAgents; }
        public void setMaxParticipatingAgents(int maxParticipatingAgents) { this.maxParticipatingAgents = maxParticipatingAgents; }
        
        public boolean isEnableConflictResolution() { return enableConflictResolution; }
        public void setEnableConflictResolution(boolean enableConflictResolution) { this.enableConflictResolution = enableConflictResolution; }
    }
    
    public static class Failover {
        private Duration recoveryTimeout = Duration.ofSeconds(30);
        private int maxBackupAgents = 3;
        private boolean enableAutomaticFailover = true;
        private Duration healthCheckInterval = Duration.ofSeconds(60);
        
        // Getters and setters
        public Duration getRecoveryTimeout() { return recoveryTimeout; }
        public void setRecoveryTimeout(Duration recoveryTimeout) { this.recoveryTimeout = recoveryTimeout; }
        
        public int getMaxBackupAgents() { return maxBackupAgents; }
        public void setMaxBackupAgents(int maxBackupAgents) { this.maxBackupAgents = maxBackupAgents; }
        
        public boolean isEnableAutomaticFailover() { return enableAutomaticFailover; }
        public void setEnableAutomaticFailover(boolean enableAutomaticFailover) { this.enableAutomaticFailover = enableAutomaticFailover; }
        
        public Duration getHealthCheckInterval() { return healthCheckInterval; }
        public void setHealthCheckInterval(Duration healthCheckInterval) { this.healthCheckInterval = healthCheckInterval; }
    }
    
    // Main getters and setters
    public Registry getRegistry() { return registry; }
    public void setRegistry(Registry registry) { this.registry = registry; }
    
    public Performance getPerformance() { return performance; }
    public void setPerformance(Performance performance) { this.performance = performance; }
    
    public Collaboration getCollaboration() { return collaboration; }
    public void setCollaboration(Collaboration collaboration) { this.collaboration = collaboration; }
    
    public Failover getFailover() { return failover; }
    public void setFailover(Failover failover) { this.failover = failover; }
}
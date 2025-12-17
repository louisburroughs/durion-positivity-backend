package com.positivity.agent.context;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Context model for configuration management scenarios
 * Supports ConfigurationManagementAgent operations and guidance
 * 
 * Requirements: REQ-014.1 - Application configuration guidance
 */
public class ConfigurationContext {
    
    private final String contextId;
    private final String sessionId;
    private final Instant createdAt;
    private Instant lastUpdated;
    
    // Configuration sources and management
    private final List<String> configSources;
    private final Map<String, Object> configSourceSettings;
    private final List<String> configFormats;
    private final Map<String, String> configValidation;
    
    // Feature flags and toggles
    private final List<String> featureFlags;
    private final Map<String, Object> flagConfigurations;
    private final List<String> rolloutStrategies;
    private final Map<String, String> flagTargeting;
    
    // Secrets management
    private final List<String> secretsManagers;
    private final Map<String, Object> secretsConfigurations;
    private final List<String> rotationPolicies;
    private final Map<String, String> accessPolicies;
    
    // Environment-specific configurations
    private final List<String> environments;
    private final Map<String, Map<String, Object>> environmentConfigs;
    private final List<String> configProfiles;
    private final Map<String, String> profileMappings;
    
    // Configuration validation and monitoring
    private final List<String> validationRules;
    private final List<String> configMonitoring;
    private final Map<String, String> driftDetection;
    private final List<String> configAuditing;
    
    public ConfigurationContext(String contextId, String sessionId) {
        this.contextId = contextId;
        this.sessionId = sessionId;
        this.createdAt = Instant.now();
        this.lastUpdated = Instant.now();
        
        this.configSources = new ArrayList<>();
        this.configSourceSettings = new HashMap<>();
        this.configFormats = new ArrayList<>();
        this.configValidation = new HashMap<>();
        
        this.featureFlags = new ArrayList<>();
        this.flagConfigurations = new HashMap<>();
        this.rolloutStrategies = new ArrayList<>();
        this.flagTargeting = new HashMap<>();
        
        this.secretsManagers = new ArrayList<>();
        this.secretsConfigurations = new HashMap<>();
        this.rotationPolicies = new ArrayList<>();
        this.accessPolicies = new HashMap<>();
        
        this.environments = new ArrayList<>();
        this.environmentConfigs = new HashMap<>();
        this.configProfiles = new ArrayList<>();
        this.profileMappings = new HashMap<>();
        
        this.validationRules = new ArrayList<>();
        this.configMonitoring = new ArrayList<>();
        this.driftDetection = new HashMap<>();
        this.configAuditing = new ArrayList<>();
    }
    
    // Configuration source management
    public void addConfigSource(String source, Map<String, Object> settings) {
        if (!this.configSources.contains(source)) {
            this.configSources.add(source);
            this.configSourceSettings.put(source, settings);
            this.lastUpdated = Instant.now();
        }
    }
    
    public void addConfigFormat(String format) {
        if (!this.configFormats.contains(format)) {
            this.configFormats.add(format);
            this.lastUpdated = Instant.now();
        }
    }
    
    public void addConfigValidation(String config, String validationRule) {
        this.configValidation.put(config, validationRule);
        this.lastUpdated = Instant.now();
    }
    
    // Feature flag management
    public void addFeatureFlag(String flag, Map<String, Object> configuration) {
        if (!this.featureFlags.contains(flag)) {
            this.featureFlags.add(flag);
            this.flagConfigurations.put(flag, configuration);
            this.lastUpdated = Instant.now();
        }
    }
    
    public void addRolloutStrategy(String strategy) {
        if (!this.rolloutStrategies.contains(strategy)) {
            this.rolloutStrategies.add(strategy);
            this.lastUpdated = Instant.now();
        }
    }
    
    public void addFlagTargeting(String flag, String targeting) {
        this.flagTargeting.put(flag, targeting);
        this.lastUpdated = Instant.now();
    }
    
    // Secrets management
    public void addSecretsManager(String manager, Map<String, Object> configuration) {
        if (!this.secretsManagers.contains(manager)) {
            this.secretsManagers.add(manager);
            this.secretsConfigurations.put(manager, configuration);
            this.lastUpdated = Instant.now();
        }
    }
    
    public void addRotationPolicy(String policy) {
        if (!this.rotationPolicies.contains(policy)) {
            this.rotationPolicies.add(policy);
            this.lastUpdated = Instant.now();
        }
    }
    
    public void addAccessPolicy(String secret, String policy) {
        this.accessPolicies.put(secret, policy);
        this.lastUpdated = Instant.now();
    }
    
    // Environment configuration management
    public void addEnvironment(String environment, Map<String, Object> config) {
        if (!this.environments.contains(environment)) {
            this.environments.add(environment);
            this.environmentConfigs.put(environment, config);
            this.lastUpdated = Instant.now();
        }
    }
    
    public void addConfigProfile(String profile) {
        if (!this.configProfiles.contains(profile)) {
            this.configProfiles.add(profile);
            this.lastUpdated = Instant.now();
        }
    }
    
    public void addProfileMapping(String profile, String environment) {
        this.profileMappings.put(profile, environment);
        this.lastUpdated = Instant.now();
    }
    
    // Configuration validation and monitoring
    public void addValidationRule(String rule) {
        if (!this.validationRules.contains(rule)) {
            this.validationRules.add(rule);
            this.lastUpdated = Instant.now();
        }
    }
    
    public void addConfigMonitoring(String monitoring) {
        if (!this.configMonitoring.contains(monitoring)) {
            this.configMonitoring.add(monitoring);
            this.lastUpdated = Instant.now();
        }
    }
    
    public void addDriftDetection(String config, String detection) {
        this.driftDetection.put(config, detection);
        this.lastUpdated = Instant.now();
    }
    
    public void addConfigAuditing(String auditing) {
        if (!this.configAuditing.contains(auditing)) {
            this.configAuditing.add(auditing);
            this.lastUpdated = Instant.now();
        }
    }
    
    // Context validation
    public boolean isValid() {
        return !configSources.isEmpty() || !featureFlags.isEmpty() || !secretsManagers.isEmpty();
    }
    
    public boolean hasFeatureFlags() {
        return !featureFlags.isEmpty() && !rolloutStrategies.isEmpty();
    }
    
    public boolean hasSecretsManagement() {
        return !secretsManagers.isEmpty() && !rotationPolicies.isEmpty();
    }
    
    public boolean hasEnvironmentIsolation() {
        return environments.size() > 1 && !environmentConfigs.isEmpty();
    }
    
    public boolean hasConfigValidation() {
        return !validationRules.isEmpty() || !driftDetection.isEmpty();
    }
    
    // Getters
    public String getContextId() { return contextId; }
    public String getSessionId() { return sessionId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastUpdated() { return lastUpdated; }
    
    public List<String> getConfigSources() { return new ArrayList<>(configSources); }
    public Map<String, Object> getConfigSourceSettings() { return new HashMap<>(configSourceSettings); }
    public List<String> getConfigFormats() { return new ArrayList<>(configFormats); }
    public Map<String, String> getConfigValidation() { return new HashMap<>(configValidation); }
    
    public List<String> getFeatureFlags() { return new ArrayList<>(featureFlags); }
    public Map<String, Object> getFlagConfigurations() { return new HashMap<>(flagConfigurations); }
    public List<String> getRolloutStrategies() { return new ArrayList<>(rolloutStrategies); }
    public Map<String, String> getFlagTargeting() { return new HashMap<>(flagTargeting); }
    
    public List<String> getSecretsManagers() { return new ArrayList<>(secretsManagers); }
    public Map<String, Object> getSecretsConfigurations() { return new HashMap<>(secretsConfigurations); }
    public List<String> getRotationPolicies() { return new ArrayList<>(rotationPolicies); }
    public Map<String, String> getAccessPolicies() { return new HashMap<>(accessPolicies); }
    
    public List<String> getEnvironments() { return new ArrayList<>(environments); }
    public Map<String, Map<String, Object>> getEnvironmentConfigs() { return new HashMap<>(environmentConfigs); }
    public List<String> getConfigProfiles() { return new ArrayList<>(configProfiles); }
    public Map<String, String> getProfileMappings() { return new HashMap<>(profileMappings); }
    
    public List<String> getValidationRules() { return new ArrayList<>(validationRules); }
    public List<String> getConfigMonitoring() { return new ArrayList<>(configMonitoring); }
    public Map<String, String> getDriftDetection() { return new HashMap<>(driftDetection); }
    public List<String> getConfigAuditing() { return new ArrayList<>(configAuditing); }
    
    @Override
    public String toString() {
        return String.format("ConfigurationContext{id='%s', session='%s', sources=%d, flags=%d, secrets=%d}", 
                contextId, sessionId, configSources.size(), featureFlags.size(), secretsManagers.size());
    }
}
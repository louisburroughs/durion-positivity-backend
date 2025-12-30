package com.pos.agent.context;

import java.time.Instant;
import java.util.*;

/**
 * Context model for configuration management scenarios.
 * Tracks service configuration, platform details, and service mesh integration.
 */
public class ConfigurationContext {
    private String contextId;
    private String sessionId;
    private String serviceName;
    private String configurationType;
    private String platform;
    private String serviceMesh;
    private final Instant createdAt;
    private Instant lastUpdated;

    // Specialized collections for configuration sources
    private final Set<String> configSources;
    private final Map<String, Object> configSourceSettings;
    private final Set<String> configFormats;
    private final Map<String, String> configValidation;

    // Feature flags management
    private final Set<String> featureFlags;
    private final Map<String, Object> flagConfigurations;
    private final Set<String> rolloutStrategies;
    private final Map<String, String> flagTargeting;

    // Secrets management
    private final Set<String> secretsManagers;
    private final Map<String, Object> secretsConfigurations;
    private final Set<String> rotationPolicies;
    private final Map<String, String> accessPolicies;

    // Environment management
    private final Set<String> environments;
    private final Map<String, Object> environmentConfigs;
    private final Set<String> configProfiles;
    private final Map<String, String> profileMappings;

    // Validation and monitoring
    private final Set<String> validationRules;
    private final Set<String> configMonitoring;
    private final Map<String, String> driftDetection;
    private final Set<String> configAuditing;

    public ConfigurationContext(String contextId, String sessionId) {
        this.contextId = Objects.requireNonNull(contextId, "Context ID cannot be null");
        this.sessionId = Objects.requireNonNull(sessionId, "Session ID cannot be null");
        this.createdAt = Instant.now();
        this.lastUpdated = Instant.now();

        // Initialize configuration sources
        this.configSources = new LinkedHashSet<>();
        this.configSourceSettings = new HashMap<>();
        this.configFormats = new LinkedHashSet<>();
        this.configValidation = new HashMap<>();

        // Initialize feature flags
        this.featureFlags = new LinkedHashSet<>();
        this.flagConfigurations = new HashMap<>();
        this.rolloutStrategies = new LinkedHashSet<>();
        this.flagTargeting = new HashMap<>();

        // Initialize secrets management
        this.secretsManagers = new LinkedHashSet<>();
        this.secretsConfigurations = new HashMap<>();
        this.rotationPolicies = new LinkedHashSet<>();
        this.accessPolicies = new HashMap<>();

        // Initialize environment management
        this.environments = new LinkedHashSet<>();
        this.environmentConfigs = new HashMap<>();
        this.configProfiles = new LinkedHashSet<>();
        this.profileMappings = new HashMap<>();

        // Initialize validation and monitoring
        this.validationRules = new LinkedHashSet<>();
        this.configMonitoring = new LinkedHashSet<>();
        this.driftDetection = new HashMap<>();
        this.configAuditing = new LinkedHashSet<>();
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

    public String getConfigurationType() {
        return configurationType;
    }

    public void setConfigurationType(String configurationType) {
        this.configurationType = configurationType;
        updateTimestamp();
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
        updateTimestamp();
    }

    public String getServiceMesh() {
        return serviceMesh;
    }

    public void setServiceMesh(String serviceMesh) {
        this.serviceMesh = serviceMesh;
        updateTimestamp();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUpdated() {
        return lastUpdated;
    }

    public Set<String> getConfigSources() {
        return new LinkedHashSet<>(configSources);
    }

    public void addConfigSource(String source, Map<String, Object> config) {
        if (!this.configSources.contains(source)) {
            this.configSources.add(source);
            this.configSourceSettings.put(source, config);
            updateTimestamp();
        }
    }

    public Map<String, Object> getConfigSourceSettings() {
        return new HashMap<>(configSourceSettings);
    }

    public Set<String> getConfigFormats() {
        return new LinkedHashSet<>(configFormats);
    }

    public void addConfigFormat(String format) {
        this.configFormats.add(format);
        updateTimestamp();
    }

    public Map<String, String> getConfigValidation() {
        return new HashMap<>(configValidation);
    }

    public void addConfigValidation(String key, String validation) {
        this.configValidation.put(key, validation);
        updateTimestamp();
    }

    // Feature flags methods
    public Set<String> getFeatureFlags() {
        return new LinkedHashSet<>(featureFlags);
    }

    public void addFeatureFlag(String flag, Map<String, Object> config) {
        if (!this.featureFlags.contains(flag)) {
            this.featureFlags.add(flag);
            this.flagConfigurations.put(flag, config);
            updateTimestamp();
        }
    }

    public Map<String, Object> getFlagConfigurations() {
        return new HashMap<>(flagConfigurations);
    }

    public Set<String> getRolloutStrategies() {
        return new LinkedHashSet<>(rolloutStrategies);
    }

    public void addRolloutStrategy(String strategy) {
        this.rolloutStrategies.add(strategy);
        updateTimestamp();
    }

    public Map<String, String> getFlagTargeting() {
        return new HashMap<>(flagTargeting);
    }

    public void addFlagTargeting(String flag, String targeting) {
        this.flagTargeting.put(flag, targeting);
        updateTimestamp();
    }

    // Secrets management methods
    public Set<String> getSecretsManagers() {
        return new LinkedHashSet<>(secretsManagers);
    }

    public void addSecretsManager(String manager, Map<String, Object> config) {
        if (!this.secretsManagers.contains(manager)) {
            this.secretsManagers.add(manager);
            this.secretsConfigurations.put(manager, config);
            updateTimestamp();
        }
    }

    public Map<String, Object> getSecretsConfigurations() {
        return new HashMap<>(secretsConfigurations);
    }

    public Set<String> getRotationPolicies() {
        return new LinkedHashSet<>(rotationPolicies);
    }

    public void addRotationPolicy(String policy) {
        this.rotationPolicies.add(policy);
        updateTimestamp();
    }

    public Map<String, String> getAccessPolicies() {
        return new HashMap<>(accessPolicies);
    }

    public void addAccessPolicy(String secret, String policy) {
        this.accessPolicies.put(secret, policy);
        updateTimestamp();
    }

    // Environment management methods
    public Set<String> getEnvironments() {
        return new LinkedHashSet<>(environments);
    }

    public void addEnvironment(String environment, Map<String, Object> config) {
        if (!this.environments.contains(environment)) {
            this.environments.add(environment);
            this.environmentConfigs.put(environment, config);
            updateTimestamp();
        }
    }

    public Map<String, Object> getEnvironmentConfigs() {
        return new HashMap<>(environmentConfigs);
    }

    public Set<String> getConfigProfiles() {
        return new LinkedHashSet<>(configProfiles);
    }

    public void addConfigProfile(String profile) {
        this.configProfiles.add(profile);
        updateTimestamp();
    }

    public Map<String, String> getProfileMappings() {
        return new HashMap<>(profileMappings);
    }

    public void addProfileMapping(String profile, String environment) {
        this.profileMappings.put(profile, environment);
        updateTimestamp();
    }

    // Validation and monitoring methods
    public Set<String> getValidationRules() {
        return new LinkedHashSet<>(validationRules);
    }

    public void addValidationRule(String rule) {
        this.validationRules.add(rule);
        updateTimestamp();
    }

    public Set<String> getConfigMonitoring() {
        return new LinkedHashSet<>(configMonitoring);
    }

    public void addConfigMonitoring(String monitoring) {
        this.configMonitoring.add(monitoring);
        updateTimestamp();
    }

    public Map<String, String> getDriftDetection() {
        return new HashMap<>(driftDetection);
    }

    public void addDriftDetection(String config, String detection) {
        this.driftDetection.put(config, detection);
        updateTimestamp();
    }

    public Set<String> getConfigAuditing() {
        return new LinkedHashSet<>(configAuditing);
    }

    public void addConfigAuditing(String auditing) {
        this.configAuditing.add(auditing);
        updateTimestamp();
    }

    // Validation state methods
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
        return environments.size() >= 2;
    }

    public boolean hasConfigValidation() {
        return !validationRules.isEmpty();
    }

    private void updateTimestamp() {
        this.lastUpdated = Instant.now();
    }

    @Override
    public String toString() {
        return "ConfigurationContext{" +
                "contextId='" + contextId + '\'' +
                ", sessionId='" + sessionId + '\'' +
                ", serviceName='" + serviceName + '\'' +
                ", configurationType='" + configurationType + '\'' +
                ", platform='" + platform + '\'' +
                ", serviceMesh='" + serviceMesh + '\'' +
                ", sources=" + configSources.size() +
                ", flags=" + featureFlags.size() +
                ", secrets=" + secretsManagers.size() +
                ", createdAt=" + createdAt +
                ", lastUpdated=" + lastUpdated +
                '}';
    }
}

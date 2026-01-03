package com.pos.agent.context;

import java.time.Instant;
import java.util.*;

/**
 * Context model for configuration management scenarios.
 * Tracks service configuration, platform details, and service mesh integration.
 */
public class ConfigurationContext extends AgentContext {
    private String serviceName;
    private String configurationType;
    private String platform;
    private String serviceMesh;

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

    private ConfigurationContext(Builder builder) {
        super(builder);
        this.serviceName = builder.serviceName;
        this.configurationType = builder.configurationType;
        this.platform = builder.platform;
        this.serviceMesh = builder.serviceMesh;

        // Initialize configuration sources
        this.configSources = builder.configSources;
        this.configSourceSettings = builder.configSourceSettings;
        this.configFormats = builder.configFormats;
        this.configValidation = builder.configValidation;

        // Initialize feature flags
        this.featureFlags = builder.featureFlags;
        this.flagConfigurations = builder.flagConfigurations;
        this.rolloutStrategies = builder.rolloutStrategies;
        this.flagTargeting = builder.flagTargeting;

        // Initialize secrets management
        this.secretsManagers = builder.secretsManagers;
        this.secretsConfigurations = builder.secretsConfigurations;
        this.rotationPolicies = builder.rotationPolicies;
        this.accessPolicies = builder.accessPolicies;

        // Initialize environment management
        this.environments = builder.environments;
        this.environmentConfigs = builder.environmentConfigs;
        this.configProfiles = builder.configProfiles;
        this.profileMappings = builder.profileMappings;

        // Initialize validation and monitoring
        this.validationRules = builder.validationRules;
        this.configMonitoring = builder.configMonitoring;
        this.driftDetection = builder.driftDetection;
        this.configAuditing = builder.configAuditing;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = Objects.requireNonNull(serviceName, "Service name cannot be null");
        updateTimestamp();
    }

    public String getConfigurationType() {
        return configurationType;
    }

    public void setConfigurationType(String configurationType) {
        this.configurationType = Objects.requireNonNull(configurationType, "Configuration type cannot be null");
        updateTimestamp();
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = Objects.requireNonNull(platform, "Platform cannot be null");
        updateTimestamp();
    }

    public String getServiceMesh() {
        return serviceMesh;
    }

    public void setServiceMesh(String serviceMesh) {
        this.serviceMesh = Objects.requireNonNull(serviceMesh, "Service mesh cannot be null");
        updateTimestamp();
    }

    public Instant getCreatedAt() {
        return super.getCreatedAt();
    }

    public Instant getLastUpdated() {
        return super.getLastUpdated();
    }

    public Set<String> getConfigSources() {
        return new LinkedHashSet<>(configSources);
    }

    public void addConfigSource(String source, Map<String, Object> config) {
        Objects.requireNonNull(source, "Config source cannot be null");
        Objects.requireNonNull(config, "Config source configuration cannot be null");
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
        Objects.requireNonNull(format, "Config format cannot be null");
        this.configFormats.add(format);
        updateTimestamp();
    }

    public Map<String, String> getConfigValidation() {
        return new HashMap<>(configValidation);
    }

    public void addConfigValidation(String key, String validation) {
        Objects.requireNonNull(key, "Config validation key cannot be null");
        Objects.requireNonNull(validation, "Config validation value cannot be null");
        this.configValidation.put(key, validation);
        updateTimestamp();
    }

    // Feature flags methods
    public Set<String> getFeatureFlags() {
        return new LinkedHashSet<>(featureFlags);
    }

    public void addFeatureFlag(String flag, Map<String, Object> config) {
        Objects.requireNonNull(flag, "Feature flag cannot be null");
        Objects.requireNonNull(config, "Feature flag configuration cannot be null");
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
        Objects.requireNonNull(strategy, "Rollout strategy cannot be null");
        this.rolloutStrategies.add(strategy);
        updateTimestamp();
    }

    public Map<String, String> getFlagTargeting() {
        return new HashMap<>(flagTargeting);
    }

    public void addFlagTargeting(String flag, String targeting) {
        Objects.requireNonNull(flag, "Flag name cannot be null");
        Objects.requireNonNull(targeting, "Flag targeting cannot be null");
        this.flagTargeting.put(flag, targeting);
        updateTimestamp();
    }

    // Secrets management methods
    public Set<String> getSecretsManagers() {
        return new LinkedHashSet<>(secretsManagers);
    }

    public void addSecretsManager(String manager, Map<String, Object> config) {
        Objects.requireNonNull(manager, "Secrets manager cannot be null");
        Objects.requireNonNull(config, "Secrets manager configuration cannot be null");
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
        Objects.requireNonNull(policy, "Rotation policy cannot be null");
        this.rotationPolicies.add(policy);
        updateTimestamp();
    }

    public Map<String, String> getAccessPolicies() {
        return new HashMap<>(accessPolicies);
    }

    public void addAccessPolicy(String secret, String policy) {
        Objects.requireNonNull(secret, "Secret name cannot be null");
        Objects.requireNonNull(policy, "Access policy cannot be null");
        this.accessPolicies.put(secret, policy);
        updateTimestamp();
    }

    // Environment management methods
    public Set<String> getEnvironments() {
        return new LinkedHashSet<>(environments);
    }

    public void addEnvironment(String environment, Map<String, Object> config) {
        Objects.requireNonNull(environment, "Environment cannot be null");
        Objects.requireNonNull(config, "Environment configuration cannot be null");
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
        Objects.requireNonNull(profile, "Config profile cannot be null");
        this.configProfiles.add(profile);
        updateTimestamp();
    }

    public Map<String, String> getProfileMappings() {
        return new HashMap<>(profileMappings);
    }

    public void addProfileMapping(String profile, String environment) {
        Objects.requireNonNull(profile, "Profile cannot be null");
        Objects.requireNonNull(environment, "Environment cannot be null");
        this.profileMappings.put(profile, environment);
        updateTimestamp();
    }

    // Validation and monitoring methods
    public Set<String> getValidationRules() {
        return new LinkedHashSet<>(validationRules);
    }

    public void addValidationRule(String rule) {
        Objects.requireNonNull(rule, "Validation rule cannot be null");
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

    protected void updateTimestamp() {
        super.updateTimestamp();
    }

    @Override
    public String toString() {
        return "ConfigurationContext{" +
                "contextId='" + getContextId() + '\'' +
                ", sessionId='" + getSessionId() + '\'' +
                ", serviceName='" + serviceName + '\'' +
                ", configurationType='" + configurationType + '\'' +
                ", platform='" + platform + '\'' +
                ", serviceMesh='" + serviceMesh + '\'' +
                ", sources=" + configSources.size() +
                ", flags=" + featureFlags.size() +
                ", secrets=" + secretsManagers.size() +
                ", createdAt=" + getCreatedAt() +
                ", lastUpdated=" + getLastUpdated() +
                '}';
    }

    public static class Builder extends AgentContext.Builder<Builder> {
        private String serviceName;
        private String configurationType;
        private String platform;
        private String serviceMesh;

        private Set<String> configSources = new LinkedHashSet<>();
        private Map<String, Object> configSourceSettings = new HashMap<>();
        private Set<String> configFormats = new LinkedHashSet<>();
        private Map<String, String> configValidation = new HashMap<>();

        private Set<String> featureFlags = new LinkedHashSet<>();
        private Map<String, Object> flagConfigurations = new HashMap<>();
        private Set<String> rolloutStrategies = new LinkedHashSet<>();
        private Map<String, String> flagTargeting = new HashMap<>();

        private Set<String> secretsManagers = new LinkedHashSet<>();
        private Map<String, Object> secretsConfigurations = new HashMap<>();
        private Set<String> rotationPolicies = new LinkedHashSet<>();
        private Map<String, String> accessPolicies = new HashMap<>();

        private Set<String> environments = new LinkedHashSet<>();
        private Map<String, Object> environmentConfigs = new HashMap<>();
        private Set<String> configProfiles = new LinkedHashSet<>();
        private Map<String, String> profileMappings = new HashMap<>();

        private Set<String> validationRules = new LinkedHashSet<>();
        private Set<String> configMonitoring = new LinkedHashSet<>();
        private Map<String, String> driftDetection = new HashMap<>();
        private Set<String> configAuditing = new LinkedHashSet<>();

        public Builder() {
            agentDomain("configuration");
            contextType("configuration-context");
        }

        public Builder serviceName(String serviceName) {
            this.serviceName = serviceName;
            return this;
        }

        public Builder configurationType(String configurationType) {
            this.configurationType = configurationType;
            return this;
        }

        public Builder platform(String platform) {
            this.platform = platform;
            return this;
        }

        public Builder serviceMesh(String serviceMesh) {
            this.serviceMesh = serviceMesh;
            return this;
        }

        public Builder addConfigSource(String source, Map<String, Object> config) {
            if (source != null) {
                this.configSources.add(source);
                if (config != null) {
                    this.configSourceSettings.put(source, config);
                }
            }
            return this;
        }

        public Builder configSources(Set<String> sources) {
            if (sources != null) {
                this.configSources.addAll(sources);
            }
            return this;
        }

        public Builder configSourceSettings(Map<String, Object> settings) {
            if (settings != null) {
                this.configSourceSettings.putAll(settings);
            }
            return this;
        }

        public Builder addConfigFormat(String format) {
            if (format != null) {
                this.configFormats.add(format);
            }
            return this;
        }

        public Builder configFormats(Set<String> formats) {
            if (formats != null) {
                this.configFormats.addAll(formats);
            }
            return this;
        }

        public Builder addConfigValidation(String key, String validation) {
            if (key != null && validation != null) {
                this.configValidation.put(key, validation);
            }
            return this;
        }

        public Builder configValidation(Map<String, String> validation) {
            if (validation != null) {
                this.configValidation.putAll(validation);
            }
            return this;
        }

        public Builder addFeatureFlag(String flag, Map<String, Object> config) {
            if (flag != null) {
                this.featureFlags.add(flag);
                if (config != null) {
                    this.flagConfigurations.put(flag, config);
                }
            }
            return this;
        }

        public Builder featureFlags(Set<String> flags) {
            if (flags != null) {
                this.featureFlags.addAll(flags);
            }
            return this;
        }

        public Builder flagConfigurations(Map<String, Object> configs) {
            if (configs != null) {
                this.flagConfigurations.putAll(configs);
            }
            return this;
        }

        public Builder addRolloutStrategy(String strategy) {
            if (strategy != null) {
                this.rolloutStrategies.add(strategy);
            }
            return this;
        }

        public Builder rolloutStrategies(Set<String> strategies) {
            if (strategies != null) {
                this.rolloutStrategies.addAll(strategies);
            }
            return this;
        }

        public Builder addFlagTargeting(String flag, String targeting) {
            if (flag != null && targeting != null) {
                this.flagTargeting.put(flag, targeting);
            }
            return this;
        }

        public Builder flagTargeting(Map<String, String> targeting) {
            if (targeting != null) {
                this.flagTargeting.putAll(targeting);
            }
            return this;
        }

        public Builder addSecretsManager(String manager, Map<String, Object> config) {
            if (manager != null) {
                this.secretsManagers.add(manager);
                if (config != null) {
                    this.secretsConfigurations.put(manager, config);
                }
            }
            return this;
        }

        public Builder secretsManagers(Set<String> managers) {
            if (managers != null) {
                this.secretsManagers.addAll(managers);
            }
            return this;
        }

        public Builder secretsConfigurations(Map<String, Object> configs) {
            if (configs != null) {
                this.secretsConfigurations.putAll(configs);
            }
            return this;
        }

        public Builder addRotationPolicy(String policy) {
            if (policy != null) {
                this.rotationPolicies.add(policy);
            }
            return this;
        }

        public Builder rotationPolicies(Set<String> policies) {
            if (policies != null) {
                this.rotationPolicies.addAll(policies);
            }
            return this;
        }

        public Builder addAccessPolicy(String secret, String policy) {
            if (secret != null && policy != null) {
                this.accessPolicies.put(secret, policy);
            }
            return this;
        }

        public Builder accessPolicies(Map<String, String> policies) {
            if (policies != null) {
                this.accessPolicies.putAll(policies);
            }
            return this;
        }

        public Builder addEnvironment(String environment, Map<String, Object> config) {
            if (environment != null) {
                this.environments.add(environment);
                if (config != null) {
                    this.environmentConfigs.put(environment, config);
                }
            }
            return this;
        }

        public Builder environments(Set<String> environments) {
            if (environments != null) {
                this.environments.addAll(environments);
            }
            return this;
        }

        public Builder environmentConfigs(Map<String, Object> configs) {
            if (configs != null) {
                this.environmentConfigs.putAll(configs);
            }
            return this;
        }

        public Builder addConfigProfile(String profile) {
            if (profile != null) {
                this.configProfiles.add(profile);
            }
            return this;
        }

        public Builder configProfiles(Set<String> profiles) {
            if (profiles != null) {
                this.configProfiles.addAll(profiles);
            }
            return this;
        }

        public Builder addProfileMapping(String profile, String environment) {
            if (profile != null && environment != null) {
                this.profileMappings.put(profile, environment);
            }
            return this;
        }

        public Builder profileMappings(Map<String, String> mappings) {
            if (mappings != null) {
                this.profileMappings.putAll(mappings);
            }
            return this;
        }

        public Builder addValidationRule(String rule) {
            if (rule != null) {
                this.validationRules.add(rule);
            }
            return this;
        }

        public Builder validationRules(Set<String> rules) {
            if (rules != null) {
                this.validationRules.addAll(rules);
            }
            return this;
        }

        public Builder addConfigMonitoring(String monitoring) {
            if (monitoring != null) {
                this.configMonitoring.add(monitoring);
            }
            return this;
        }

        public Builder configMonitoring(Set<String> monitoring) {
            if (monitoring != null) {
                this.configMonitoring.addAll(monitoring);
            }
            return this;
        }

        public Builder addDriftDetection(String config, String detection) {
            if (config != null && detection != null) {
                this.driftDetection.put(config, detection);
            }
            return this;
        }

        public Builder driftDetection(Map<String, String> detection) {
            if (detection != null) {
                this.driftDetection.putAll(detection);
            }
            return this;
        }

        public Builder addConfigAuditing(String auditing) {
            if (auditing != null) {
                this.configAuditing.add(auditing);
            }
            return this;
        }

        public Builder configAuditing(Set<String> auditing) {
            if (auditing != null) {
                this.configAuditing.addAll(auditing);
            }
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        public ConfigurationContext build() {
            return new ConfigurationContext(this);
        }
    }
}

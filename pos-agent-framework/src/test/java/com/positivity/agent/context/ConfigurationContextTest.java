package com.positivity.agent.context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConfigurationContext
 * Tests context model for configuration management scenarios
 * 
 * Requirements: REQ-014.1 - Application configuration guidance
 */
class ConfigurationContextTest {

    private ConfigurationContext context;
    private final String contextId = "config-context-123";
    private final String sessionId = "config-session-456";

    @BeforeEach
    void setUp() {
        context = new ConfigurationContext(contextId, sessionId);
    }

    @Test
    @DisplayName("Should initialize ConfigurationContext with correct properties")
    void testInitialization() {
        // Assert
        assertEquals(contextId, context.getContextId());
        assertEquals(sessionId, context.getSessionId());
        assertNotNull(context.getCreatedAt());
        assertNotNull(context.getLastUpdated());
        
        // Verify empty collections
        assertTrue(context.getConfigSources().isEmpty());
        assertTrue(context.getConfigSourceSettings().isEmpty());
        assertTrue(context.getConfigFormats().isEmpty());
        assertTrue(context.getConfigValidation().isEmpty());
        assertTrue(context.getFeatureFlags().isEmpty());
        assertTrue(context.getFlagConfigurations().isEmpty());
        assertTrue(context.getRolloutStrategies().isEmpty());
        assertTrue(context.getFlagTargeting().isEmpty());
        assertTrue(context.getSecretsManagers().isEmpty());
        assertTrue(context.getSecretsConfigurations().isEmpty());
        assertTrue(context.getRotationPolicies().isEmpty());
        assertTrue(context.getAccessPolicies().isEmpty());
        assertTrue(context.getEnvironments().isEmpty());
        assertTrue(context.getEnvironmentConfigs().isEmpty());
        assertTrue(context.getConfigProfiles().isEmpty());
        assertTrue(context.getProfileMappings().isEmpty());
        assertTrue(context.getValidationRules().isEmpty());
        assertTrue(context.getConfigMonitoring().isEmpty());
        assertTrue(context.getDriftDetection().isEmpty());
        assertTrue(context.getConfigAuditing().isEmpty());
        
        // Verify initial validation state
        assertFalse(context.isValid());
        assertFalse(context.hasFeatureFlags());
        assertFalse(context.hasSecretsManagement());
        assertFalse(context.hasEnvironmentIsolation());
        assertFalse(context.hasConfigValidation());
    }

    @Test
    @DisplayName("Should add configuration sources with settings")
    void testAddConfigSource() {
        // Arrange
        Map<String, Object> springCloudConfig = Map.of(
            "type", "centralized-config",
            "url", "http://config-server:8888",
            "encryption", true
        );
        
        Instant beforeUpdate = context.getLastUpdated();
        
        // Act
        context.addConfigSource("spring-cloud-config", springCloudConfig);
        
        // Assert
        assertEquals(1, context.getConfigSources().size());
        assertTrue(context.getConfigSources().contains("spring-cloud-config"));
        assertEquals(springCloudConfig, context.getConfigSourceSettings().get("spring-cloud-config"));
        assertTrue(context.getLastUpdated().isAfter(beforeUpdate));
        assertTrue(context.isValid());
        
        // Test duplicate prevention
        context.addConfigSource("spring-cloud-config", Map.of("different", "config"));
        assertEquals(1, context.getConfigSources().size());
        assertEquals(springCloudConfig, context.getConfigSourceSettings().get("spring-cloud-config"));
    }

    @Test
    @DisplayName("Should add configuration formats and validation")
    void testAddConfigFormatsAndValidation() {
        // Act
        context.addConfigFormat("yaml");
        context.addConfigFormat("properties");
        context.addConfigFormat("json");
        context.addConfigValidation("database.url", "url-format-validation");
        context.addConfigValidation("server.port", "port-range-validation");
        
        // Assert
        assertEquals(3, context.getConfigFormats().size());
        assertTrue(context.getConfigFormats().contains("yaml"));
        assertTrue(context.getConfigFormats().contains("properties"));
        assertTrue(context.getConfigFormats().contains("json"));
        
        assertEquals(2, context.getConfigValidation().size());
        assertEquals("url-format-validation", context.getConfigValidation().get("database.url"));
        assertEquals("port-range-validation", context.getConfigValidation().get("server.port"));
    }

    @Test
    @DisplayName("Should add feature flags with configurations")
    void testAddFeatureFlags() {
        // Arrange
        Map<String, Object> flagConfig = Map.of(
            "type", "boolean-flag",
            "default-value", false,
            "targeting", "user-segment"
        );
        
        Instant beforeUpdate = context.getLastUpdated();
        
        // Act
        context.addFeatureFlag("new-checkout-flow", flagConfig);
        context.addRolloutStrategy("gradual-rollout");
        context.addRolloutStrategy("canary-release");
        context.addFlagTargeting("new-checkout-flow", "premium-users");
        
        // Assert
        assertEquals(1, context.getFeatureFlags().size());
        assertTrue(context.getFeatureFlags().contains("new-checkout-flow"));
        assertEquals(flagConfig, context.getFlagConfigurations().get("new-checkout-flow"));
        
        assertEquals(2, context.getRolloutStrategies().size());
        assertTrue(context.getRolloutStrategies().contains("gradual-rollout"));
        assertTrue(context.getRolloutStrategies().contains("canary-release"));
        
        assertEquals("premium-users", context.getFlagTargeting().get("new-checkout-flow"));
        
        assertTrue(context.getLastUpdated().isAfter(beforeUpdate));
        assertTrue(context.hasFeatureFlags());
    }

    @Test
    @DisplayName("Should add secrets management components")
    void testAddSecretsManagement() {
        // Arrange
        Map<String, Object> vaultConfig = Map.of(
            "type", "enterprise-secrets",
            "url", "https://vault.company.com",
            "auth-method", "kubernetes"
        );
        
        Instant beforeUpdate = context.getLastUpdated();
        
        // Act
        context.addSecretsManager("hashicorp-vault", vaultConfig);
        context.addSecretsManager("aws-secrets-manager", Map.of("type", "cloud-secrets"));
        context.addRotationPolicy("daily-rotation");
        context.addRotationPolicy("weekly-rotation");
        context.addAccessPolicy("database-password", "read-only-policy");
        context.addAccessPolicy("api-key", "service-account-policy");
        
        // Assert
        assertEquals(2, context.getSecretsManagers().size());
        assertTrue(context.getSecretsManagers().contains("hashicorp-vault"));
        assertTrue(context.getSecretsManagers().contains("aws-secrets-manager"));
        
        assertEquals(vaultConfig, context.getSecretsConfigurations().get("hashicorp-vault"));
        
        assertEquals(2, context.getRotationPolicies().size());
        assertTrue(context.getRotationPolicies().contains("daily-rotation"));
        assertTrue(context.getRotationPolicies().contains("weekly-rotation"));
        
        assertEquals(2, context.getAccessPolicies().size());
        assertEquals("read-only-policy", context.getAccessPolicies().get("database-password"));
        assertEquals("service-account-policy", context.getAccessPolicies().get("api-key"));
        
        assertTrue(context.getLastUpdated().isAfter(beforeUpdate));
        assertTrue(context.hasSecretsManagement());
    }

    @Test
    @DisplayName("Should add environment configurations")
    void testAddEnvironmentConfigurations() {
        // Arrange
        Map<String, Object> prodConfig = Map.of(
            "database.pool-size", 20,
            "logging.level", "WARN",
            "cache.enabled", true
        );
        
        Map<String, Object> devConfig = Map.of(
            "database.pool-size", 5,
            "logging.level", "DEBUG",
            "cache.enabled", false
        );
        
        Instant beforeUpdate = context.getLastUpdated();
        
        // Act
        context.addEnvironment("production", prodConfig);
        context.addEnvironment("development", devConfig);
        context.addConfigProfile("prod");
        context.addConfigProfile("dev");
        context.addProfileMapping("prod", "production");
        context.addProfileMapping("dev", "development");
        
        // Assert
        assertEquals(2, context.getEnvironments().size());
        assertTrue(context.getEnvironments().contains("production"));
        assertTrue(context.getEnvironments().contains("development"));
        
        assertEquals(prodConfig, context.getEnvironmentConfigs().get("production"));
        assertEquals(devConfig, context.getEnvironmentConfigs().get("development"));
        
        assertEquals(2, context.getConfigProfiles().size());
        assertTrue(context.getConfigProfiles().contains("prod"));
        assertTrue(context.getConfigProfiles().contains("dev"));
        
        assertEquals("production", context.getProfileMappings().get("prod"));
        assertEquals("development", context.getProfileMappings().get("dev"));
        
        assertTrue(context.getLastUpdated().isAfter(beforeUpdate));
        assertTrue(context.hasEnvironmentIsolation());
    }

    @Test
    @DisplayName("Should add validation and monitoring components")
    void testAddValidationAndMonitoring() {
        // Arrange
        Instant beforeUpdate = context.getLastUpdated();
        
        // Act
        context.addValidationRule("schema-validation");
        context.addValidationRule("constraint-validation");
        context.addConfigMonitoring("config-change-detection");
        context.addConfigMonitoring("health-check-monitoring");
        context.addDriftDetection("database-config", "drift-alert");
        context.addDriftDetection("security-config", "immediate-alert");
        context.addConfigAuditing("change-log-auditing");
        context.addConfigAuditing("access-log-auditing");
        
        // Assert
        assertEquals(2, context.getValidationRules().size());
        assertTrue(context.getValidationRules().contains("schema-validation"));
        assertTrue(context.getValidationRules().contains("constraint-validation"));
        
        assertEquals(2, context.getConfigMonitoring().size());
        assertTrue(context.getConfigMonitoring().contains("config-change-detection"));
        assertTrue(context.getConfigMonitoring().contains("health-check-monitoring"));
        
        assertEquals(2, context.getDriftDetection().size());
        assertEquals("drift-alert", context.getDriftDetection().get("database-config"));
        assertEquals("immediate-alert", context.getDriftDetection().get("security-config"));
        
        assertEquals(2, context.getConfigAuditing().size());
        assertTrue(context.getConfigAuditing().contains("change-log-auditing"));
        assertTrue(context.getConfigAuditing().contains("access-log-auditing"));
        
        assertTrue(context.getLastUpdated().isAfter(beforeUpdate));
        assertTrue(context.hasConfigValidation());
    }

    @Test
    @DisplayName("Should validate context states correctly")
    void testContextValidation() {
        // Initially invalid
        assertFalse(context.isValid());
        assertFalse(context.hasFeatureFlags());
        assertFalse(context.hasSecretsManagement());
        assertFalse(context.hasEnvironmentIsolation());
        assertFalse(context.hasConfigValidation());
        
        // Valid with config source
        context.addConfigSource("spring-cloud-config", Map.of());
        assertTrue(context.isValid());
        
        // Valid with feature flag
        ConfigurationContext flagContext = new ConfigurationContext("flag-ctx", "session");
        flagContext.addFeatureFlag("test-flag", Map.of());
        assertTrue(flagContext.isValid());
        
        // Valid with secrets manager
        ConfigurationContext secretsContext = new ConfigurationContext("secrets-ctx", "session");
        secretsContext.addSecretsManager("vault", Map.of());
        assertTrue(secretsContext.isValid());
        
        // Has feature flags (needs both flags and strategies)
        context.addFeatureFlag("test-flag", Map.of());
        context.addRolloutStrategy("gradual");
        assertTrue(context.hasFeatureFlags());
        
        // Has secrets management (needs both managers and policies)
        context.addSecretsManager("vault", Map.of());
        context.addRotationPolicy("daily");
        assertTrue(context.hasSecretsManagement());
        
        // Has environment isolation (needs multiple environments)
        context.addEnvironment("dev", Map.of());
        context.addEnvironment("prod", Map.of());
        assertTrue(context.hasEnvironmentIsolation());
        
        // Has config validation
        context.addValidationRule("schema-validation");
        assertTrue(context.hasConfigValidation());
    }

    @Test
    @DisplayName("Should prevent duplicate entries")
    void testDuplicatePrevention() {
        // Act - Add same items multiple times
        context.addConfigFormat("yaml");
        context.addConfigFormat("yaml");
        
        context.addRolloutStrategy("gradual");
        context.addRolloutStrategy("gradual");
        
        context.addRotationPolicy("daily");
        context.addRotationPolicy("daily");
        
        context.addConfigProfile("prod");
        context.addConfigProfile("prod");
        
        // Assert - Should only have one of each
        assertEquals(1, context.getConfigFormats().size());
        assertEquals(1, context.getRolloutStrategies().size());
        assertEquals(1, context.getRotationPolicies().size());
        assertEquals(1, context.getConfigProfiles().size());
    }

    @Test
    @DisplayName("Should return defensive copies of collections")
    void testDefensiveCopies() {
        // Arrange
        context.addConfigSource("spring-cloud-config", Map.of("type", "centralized"));
        context.addFeatureFlag("test-flag", Map.of("type", "boolean"));
        context.addSecretsManager("vault", Map.of("type", "enterprise"));
        
        // Act - Try to modify returned collections
        context.getConfigSources().add("should-not-be-added");
        context.getFeatureFlags().add("should-not-be-added");
        context.getSecretsManagers().add("should-not-be-added");
        context.getConfigSourceSettings().put("should-not", "be-added");
        context.getFlagConfigurations().put("should-not", "be-added");
        context.getSecretsConfigurations().put("should-not", "be-added");
        
        // Assert - Original collections should be unchanged
        assertEquals(1, context.getConfigSources().size());
        assertEquals(1, context.getFeatureFlags().size());
        assertEquals(1, context.getSecretsManagers().size());
        assertEquals(1, context.getConfigSourceSettings().size());
        assertEquals(1, context.getFlagConfigurations().size());
        assertEquals(1, context.getSecretsConfigurations().size());
        
        assertTrue(context.getConfigSources().contains("spring-cloud-config"));
        assertFalse(context.getConfigSources().contains("should-not-be-added"));
    }

    @Test
    @DisplayName("Should have meaningful toString representation")
    void testToString() {
        // Arrange
        context.addConfigSource("spring-cloud-config", Map.of());
        context.addFeatureFlag("test-flag", Map.of());
        context.addSecretsManager("vault", Map.of());
        
        // Act
        String toString = context.toString();
        
        // Assert
        assertNotNull(toString);
        assertTrue(toString.contains("ConfigurationContext"));
        assertTrue(toString.contains(contextId));
        assertTrue(toString.contains(sessionId));
        assertTrue(toString.contains("sources=1"));
        assertTrue(toString.contains("flags=1"));
        assertTrue(toString.contains("secrets=1"));
    }

    @Test
    @DisplayName("Should handle complex configuration management scenario")
    void testComplexConfigurationScenario() {
        // Arrange & Act - Build a complex configuration context
        
        // Configuration sources
        context.addConfigSource("spring-cloud-config", Map.of(
            "type", "centralized-config",
            "url", "http://config-server:8888",
            "encryption", true,
            "profiles", "dev,test,prod"
        ));
        context.addConfigSource("consul", Map.of(
            "type", "service-discovery-config",
            "url", "http://consul:8500",
            "datacenter", "dc1"
        ));
        context.addConfigSource("etcd", Map.of(
            "type", "distributed-config",
            "endpoints", "etcd1:2379,etcd2:2379,etcd3:2379"
        ));
        context.addConfigFormat("yaml");
        context.addConfigFormat("properties");
        context.addConfigFormat("json");
        context.addConfigValidation("database.url", "url-format-validation");
        context.addConfigValidation("server.port", "port-range-validation");
        context.addConfigValidation("security.jwt.secret", "base64-validation");
        
        // Feature flags
        context.addFeatureFlag("new-checkout-flow", Map.of(
            "type", "boolean-flag",
            "default-value", false,
            "targeting", "user-segment"
        ));
        context.addFeatureFlag("enhanced-search", Map.of(
            "type", "percentage-rollout",
            "percentage", 25,
            "targeting", "premium-users"
        ));
        context.addFeatureFlag("mobile-app-v2", Map.of(
            "type", "kill-switch",
            "default-value", true
        ));
        context.addRolloutStrategy("gradual-rollout");
        context.addRolloutStrategy("canary-release");
        context.addRolloutStrategy("blue-green-toggle");
        context.addFlagTargeting("new-checkout-flow", "premium-users");
        context.addFlagTargeting("enhanced-search", "beta-testers");
        
        // Secrets management
        context.addSecretsManager("hashicorp-vault", Map.of(
            "type", "enterprise-secrets",
            "url", "https://vault.company.com",
            "auth-method", "kubernetes",
            "namespace", "production"
        ));
        context.addSecretsManager("aws-secrets-manager", Map.of(
            "type", "cloud-secrets",
            "region", "us-east-1",
            "kms-key", "arn:aws:kms:us-east-1:123456789:key/12345"
        ));
        context.addSecretsManager("kubernetes-secrets", Map.of(
            "type", "k8s-secrets",
            "namespace", "default"
        ));
        context.addRotationPolicy("daily-rotation");
        context.addRotationPolicy("weekly-rotation");
        context.addRotationPolicy("monthly-rotation");
        context.addAccessPolicy("database-password", "read-only-policy");
        context.addAccessPolicy("api-key", "service-account-policy");
        context.addAccessPolicy("jwt-secret", "admin-only-policy");
        
        // Environment configurations
        context.addEnvironment("development", Map.of(
            "database.pool-size", 5,
            "logging.level", "DEBUG",
            "cache.enabled", false,
            "feature-flags.enabled", true
        ));
        context.addEnvironment("testing", Map.of(
            "database.pool-size", 10,
            "logging.level", "INFO",
            "cache.enabled", true,
            "feature-flags.enabled", true
        ));
        context.addEnvironment("staging", Map.of(
            "database.pool-size", 15,
            "logging.level", "WARN",
            "cache.enabled", true,
            "feature-flags.enabled", true
        ));
        context.addEnvironment("production", Map.of(
            "database.pool-size", 20,
            "logging.level", "ERROR",
            "cache.enabled", true,
            "feature-flags.enabled", false
        ));
        context.addConfigProfile("dev");
        context.addConfigProfile("test");
        context.addConfigProfile("stage");
        context.addConfigProfile("prod");
        context.addProfileMapping("dev", "development");
        context.addProfileMapping("test", "testing");
        context.addProfileMapping("stage", "staging");
        context.addProfileMapping("prod", "production");
        
        // Validation and monitoring
        context.addValidationRule("schema-validation");
        context.addValidationRule("constraint-validation");
        context.addValidationRule("security-validation");
        context.addConfigMonitoring("config-change-detection");
        context.addConfigMonitoring("health-check-monitoring");
        context.addConfigMonitoring("performance-monitoring");
        context.addDriftDetection("database-config", "drift-alert");
        context.addDriftDetection("security-config", "immediate-alert");
        context.addDriftDetection("feature-flag-config", "notification-alert");
        context.addConfigAuditing("change-log-auditing");
        context.addConfigAuditing("access-log-auditing");
        context.addConfigAuditing("compliance-auditing");
        
        // Assert - Verify complex scenario
        assertEquals(3, context.getConfigSources().size());
        assertEquals(3, context.getConfigFormats().size());
        assertEquals(3, context.getConfigValidation().size());
        assertEquals(3, context.getFeatureFlags().size());
        assertEquals(3, context.getRolloutStrategies().size());
        assertEquals(2, context.getFlagTargeting().size());
        assertEquals(3, context.getSecretsManagers().size());
        assertEquals(3, context.getRotationPolicies().size());
        assertEquals(3, context.getAccessPolicies().size());
        assertEquals(4, context.getEnvironments().size());
        assertEquals(4, context.getConfigProfiles().size());
        assertEquals(4, context.getProfileMappings().size());
        assertEquals(3, context.getValidationRules().size());
        assertEquals(3, context.getConfigMonitoring().size());
        assertEquals(3, context.getDriftDetection().size());
        assertEquals(3, context.getConfigAuditing().size());
        
        // Verify validation states
        assertTrue(context.isValid());
        assertTrue(context.hasFeatureFlags());
        assertTrue(context.hasSecretsManagement());
        assertTrue(context.hasEnvironmentIsolation());
        assertTrue(context.hasConfigValidation());
        
        // Verify configurations
        Map<String, Object> springConfig = (Map<String, Object>) context.getConfigSourceSettings().get("spring-cloud-config");
        assertEquals("centralized-config", springConfig.get("type"));
        assertTrue((Boolean) springConfig.get("encryption"));
        
        Map<String, Object> vaultConfig = (Map<String, Object>) context.getSecretsConfigurations().get("hashicorp-vault");
        assertEquals("enterprise-secrets", vaultConfig.get("type"));
        assertEquals("kubernetes", vaultConfig.get("auth-method"));
        
        Map<String, Object> prodEnvConfig = (Map<String, Object>) context.getEnvironmentConfigs().get("production");
        assertEquals(20, prodEnvConfig.get("database.pool-size"));
        assertEquals("ERROR", prodEnvConfig.get("logging.level"));
        assertEquals(false, prodEnvConfig.get("feature-flags.enabled"));
    }
}
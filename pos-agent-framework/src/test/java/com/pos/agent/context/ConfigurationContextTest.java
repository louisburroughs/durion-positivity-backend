package com.pos.agent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for ConfigurationContext class.
 * Tests comprehensive configuration management across multiple categories:
 * - Service configuration
 * - Feature flags with rollout strategies
 * - Secrets management with rotation policies
 * - Environment configurations
 * - Validation rules
 */
@DisplayName("ConfigurationContext")
class ConfigurationContextTest {

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("should create ConfigurationContext using builder")
        void shouldCreateConfigurationContextUsingBuilder() {
            // When
            ConfigurationContext context = ConfigurationContext.builder().build();

            // Then
            assertThat(context).isNotNull();
            assertThat(context).isInstanceOf(AgentContext.class);
        }

        @Test
        @DisplayName("should set default agentDomain to 'configuration'")
        void shouldSetDefaultAgentDomain() {
            // When
            ConfigurationContext context = ConfigurationContext.builder().build();

            // Then
            assertThat(context.getAgentDomain()).isEqualTo("configuration");
        }

        @Test
        @DisplayName("should set default contextType to 'configuration-context'")
        void shouldSetDefaultContextType() {
            // When
            ConfigurationContext context = ConfigurationContext.builder().build();

            // Then
            assertThat(context.getContextType()).isEqualTo("configuration-context");
        }

        @Test
        @DisplayName("should set service name")
        void shouldSetServiceName() {
            // When
            ConfigurationContext context = ConfigurationContext.builder()
                    .serviceName("auth-service")
                    .build();

            // Then
            assertThat(context.getServiceName()).isEqualTo("auth-service");
        }

        @Test
        @DisplayName("should set configuration type")
        void shouldSetConfigurationType() {
            // When
            ConfigurationContext context = ConfigurationContext.builder()
                    .configurationType("microservices")
                    .build();

            // Then
            assertThat(context.getConfigurationType()).isEqualTo("microservices");
        }

        @Test
        @DisplayName("should set platform")
        void shouldSetPlatform() {
            // When
            ConfigurationContext context = ConfigurationContext.builder()
                    .platform("Kubernetes")
                    .build();

            // Then
            assertThat(context.getPlatform()).isEqualTo("Kubernetes");
        }

        @Test
        @DisplayName("should add config source")
        void shouldAddConfigSource() {
            // When
            ConfigurationContext context = ConfigurationContext.builder()
                    .addConfigSource("ConfigServer", Map.of("url", "http://config-server:8888"))
                    .build();

            // Then
            assertThat(context.getConfigSources()).contains("ConfigServer");
            assertThat(context.getConfigSourceSettings()).containsKey("ConfigServer");
        }

        @Test
        @DisplayName("should add feature flag with configurations")
        void shouldAddFeatureFlag() {
            // When
            ConfigurationContext context = ConfigurationContext.builder()
                    .addFeatureFlag("dark-mode", Map.of("enabled", true, "version", 2))
                    .addRolloutStrategy("gradual")
                    .addFlagTargeting("dark-mode", "user-segment=premium")
                    .build();

            // Then
            assertThat(context.getFeatureFlags()).contains("dark-mode");
            assertThat(context.getFlagConfigurations()).containsKey("dark-mode");
            assertThat(context.getRolloutStrategies()).contains("gradual");
        }

        @Test
        @DisplayName("should add flag targeting rules")
        void shouldAddFlagTargetingRules() {
            // When
            ConfigurationContext context = ConfigurationContext.builder()
                    .addFeatureFlag("new-dashboard", Map.of("description", "New analytics dashboard"))
                    .addFlagTargeting("new-dashboard", "user-segment=premium")
                    .build();

            // Then
            assertThat(context.getFlagTargeting()).containsEntry("new-dashboard", "user-segment=premium");
        }

        @Test
        @DisplayName("should add secrets manager")
        void shouldAddSecretsManager() {
            // When
            ConfigurationContext context = ConfigurationContext.builder()
                    .addSecretsManager("HashiCorp Vault", Map.of("url", "http://vault:8200"))
                    .build();

            // Then
            assertThat(context.getSecretsManagers()).contains("HashiCorp Vault");
            assertThat(context.getSecretsConfigurations()).containsKey("HashiCorp Vault");
        }

        @Test
        @DisplayName("should add rotation policy for secrets")
        void shouldAddRotationPolicy() {
            // When
            ConfigurationContext context = ConfigurationContext.builder()
                    .addSecretsManager("AWS Secrets Manager", Map.of("url", "https://aws.amazon.com"))
                    .addRotationPolicy("30-days")
                    .addAccessPolicy("API-KEY", "role-based-access")
                    .build();

            // Then
            assertThat(context.getRotationPolicies()).contains("30-days");
            assertThat(context.getAccessPolicies()).containsEntry("API-KEY", "role-based-access");
        }

        @Test
        @DisplayName("should add access policy for secrets")
        void shouldAddAccessPolicy() {
            // When
            ConfigurationContext context = ConfigurationContext.builder()
                    .addSecretsManager("Vault", Map.of("url", "http://vault:8200"))
                    .addAccessPolicy("db-password", "role-based-access")
                    .build();

            // Then
            assertThat(context.getAccessPolicies()).containsEntry("db-password", "role-based-access");
        }

        @Test
        @DisplayName("should add environment configuration")
        void shouldAddEnvironmentConfiguration() {
            // When
            ConfigurationContext context = ConfigurationContext.builder()
                    .addEnvironment("Production", Map.of("replicas", 3, "cpu", "2000m", "memory", "4Gi"))
                    .build();

            // Then
            assertThat(context.getEnvironments()).contains("Production");
            assertThat(context.getEnvironmentConfigs()).containsKey("Production");
        }

        @Test
        @DisplayName("should add validation rule")
        void shouldAddValidationRule() {
            // When
            ConfigurationContext context = ConfigurationContext.builder()
                    .addValidationRule("API Rate Limit")
                    .addConfigValidation("max-requests", "1000-per-minute")
                    .build();

            // Then
            assertThat(context.getValidationRules()).contains("API Rate Limit");
            assertThat(context.getConfigValidation()).containsEntry("max-requests", "1000-per-minute");
        }
    }

    @Nested
    @DisplayName("Immutability Tests")
    class ImmutabilityTests {

        @Test
        @DisplayName("should return defensive copy of config sources")
        void shouldReturnDefensiveCopyOfConfigSources() {
            // Given
            ConfigurationContext context = ConfigurationContext.builder()
                    .addConfigSource("Server1", Map.of("url", "http://server1"))
                    .build();

            // When
            var sources = context.getConfigSources();
            sources.add("Hacked");

            // Then
            assertThat(context.getConfigSources()).doesNotContain("Hacked");
        }

        @Test
        @DisplayName("should return defensive copy of feature flags")
        void shouldReturnDefensiveCopyOfFeatureFlags() {
            // Given
            ConfigurationContext context = ConfigurationContext.builder()
                    .addFeatureFlag("feature1", Map.of("description", "Feature description"))
                    .build();

            // When
            var flags = context.getFeatureFlags();
            flags.add("Hacked");

            // Then
            assertThat(context.getFeatureFlags()).doesNotContain("Hacked");
        }

        @Test
        @DisplayName("should return defensive copy of secrets managers")
        void shouldReturnDefensiveCopyOfSecretsManagers() {
            // Given
            ConfigurationContext context = ConfigurationContext.builder()
                    .addSecretsManager("Vault", Map.of("url", "http://vault"))
                    .build();

            // When
            var managers = context.getSecretsManagers();
            managers.add("Hacked");

            // Then
            assertThat(context.getSecretsManagers()).doesNotContain("Hacked");
        }
    }

    @Nested
    @DisplayName("Real-World Usage Tests")
    class RealWorldUsageTests {

        @Test
        @DisplayName("should create comprehensive microservices configuration")
        void shouldCreateComprehensiveMicroservicesConfiguration() {
            // When
            ConfigurationContext context = ConfigurationContext.builder()
                    .serviceName("order-service")
                    .configurationType("microservices")
                    .platform("Kubernetes")
                    .addConfigSource("Spring Cloud Config", Map.of("url", "http://config-server:8888"))
                    .addConfigSource("Consul", Map.of("url", "http://consul:8500"))
                    .addFeatureFlag("new-order-workflow",
                            Map.of("enabled", true, "description", "Enable improved order processing"))
                    .addRolloutStrategy("canary")
                    .addFlagTargeting("new-order-workflow", "percentage=50")
                    .addSecretsManager("HashiCorp Vault", Map.of("url", "http://vault:8200"))
                    .addRotationPolicy("30-days")
                    .addAccessPolicy("db-password", "role-based-access-control")
                    .addEnvironment("Production", Map.of("replicas", 5, "cpu", "4000m", "memory", "8Gi"))
                    .addValidationRule("Request Timeout")
                    .addValidationRule("Retry Logic")
                    .addConfigValidation("timeout", "30s")
                    .addConfigValidation("max-retries", "3")
                    .build();

            // Then
            assertThat(context.getServiceName()).isEqualTo("order-service");
            assertThat(context.getConfigSources()).hasSize(2);
            assertThat(context.getFeatureFlags()).hasSize(1);
            assertThat(context.getSecretsManagers()).hasSize(1);
            assertThat(context.getEnvironments()).hasSize(1);
            assertThat(context.getValidationRules()).hasSize(2);
        }

        @Test
        @DisplayName("should create configuration with multiple feature flags and rollout strategies")
        void shouldCreateMultipleFeatureFlagsConfiguration() {
            // When
            ConfigurationContext context = ConfigurationContext.builder()
                    .serviceName("user-service")
                    .configurationType("microservices")
                    .addFeatureFlag("oauth2-integration", Map.of("provider", "Google", "scope", "email"))
                    .addRolloutStrategy("gradual")
                    .addFlagTargeting("oauth2-integration", "user-segment=beta-testers")
                    .addFeatureFlag("api-rate-limiting", Map.of("limit", 1000, "window", "60s"))
                    .addRolloutStrategy("phased")
                    .addFlagTargeting("api-rate-limiting", "environment=production")
                    .build();

            // Then
            assertThat(context.getFeatureFlags()).hasSize(2);
            assertThat(context.getRolloutStrategies()).hasSize(2);
        }

        @Test
        @DisplayName("should create configuration with multiple secrets managers and rotation policies")
        void shouldCreateMultipleSecretsManagersConfiguration() {
            // When
            ConfigurationContext context = ConfigurationContext.builder()
                    .serviceName("payment-service")
                    .configurationType("microservices")
                    .addSecretsManager("HashiCorp Vault", Map.of("url", "http://vault:8200"))
                    .addRotationPolicy("monthly")
                    .addAccessPolicy("vault-db-pass", "least-privilege-access")
                    .addSecretsManager("AWS Secrets Manager",
                            Map.of("url", "https://us-east-1.secretsmanager.amazonaws.com"))
                    .addRotationPolicy("quarterly")
                    .addAccessPolicy("aws-api-key", "iam-policy-based")
                    .addSecretsManager("Kubernetes Secrets", Map.of("type", "in-cluster"))
                    .addRotationPolicy("every-6-months")
                    .addAccessPolicy("k8s-cert", "RBAC")
                    .build();

            // Then
            assertThat(context.getSecretsManagers()).hasSize(3);
            assertThat(context.getRotationPolicies()).hasSize(3);
            assertThat(context.getAccessPolicies()).hasSize(3);
        }

        @Test
        @DisplayName("should create configuration with multiple environments")
        void shouldCreateMultipleEnvironmentsConfiguration() {
            // When
            ConfigurationContext context = ConfigurationContext.builder()
                    .serviceName("api-gateway")
                    .platform("Kubernetes")
                    .addEnvironment("Development", Map.of("replicas", 1, "cpu", "500m", "memory", "512Mi"))
                    .addEnvironment("Staging", Map.of("replicas", 2, "cpu", "1000m", "memory", "1Gi"))
                    .addEnvironment("Production", Map.of("replicas", 5, "cpu", "2000m", "memory", "2Gi"))
                    .build();

            // Then
            assertThat(context.getEnvironments()).hasSize(3);
            assertThat(context.getEnvironmentConfigs()).hasSize(3);
        }
    }

    @Nested
    @DisplayName("Edge Cases Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("should handle empty configuration context")
        void shouldHandleEmptyConfigurationContext() {
            // When
            ConfigurationContext context = ConfigurationContext.builder().build();

            // Then
            assertThat(context.getConfigSources()).isEmpty();
            assertThat(context.getFeatureFlags()).isEmpty();
            assertThat(context.getSecretsManagers()).isEmpty();
            assertThat(context.getEnvironments()).isEmpty();
            assertThat(context.getValidationRules()).isEmpty();
        }

        @Test
        @DisplayName("should throw NullPointerException when setting null service name")
        void shouldThrowWhenSettingNullServiceName() {
            // Given
            ConfigurationContext context = ConfigurationContext.builder().serviceName("old-service").build();

            // When/Then
            assertThatThrownBy(() -> context.setServiceName(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("Service name cannot be null");
        }

        @Test
        @DisplayName("should throw NullPointerException when setting null configuration type")
        void shouldThrowWhenSettingNullConfigurationType() {
            // Given
            ConfigurationContext context = ConfigurationContext.builder().configurationType("old-type").build();

            // When/Then
            assertThatThrownBy(() -> context.setConfigurationType(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("Configuration type cannot be null");
        }

        @Test
        @DisplayName("should throw NullPointerException when setting null platform")
        void shouldThrowWhenSettingNullPlatform() {
            // Given
            ConfigurationContext context = ConfigurationContext.builder().platform("old-platform").build();

            // When/Then
            assertThatThrownBy(() -> context.setPlatform(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("Platform cannot be null");
        }

        @Test
        @DisplayName("should throw NullPointerException when adding null config source")
        void shouldThrowWhenAddingNullConfigSource() {
            // Given
            ConfigurationContext context = ConfigurationContext.builder().build();

            // When/Then
            assertThatThrownBy(() -> context.addConfigSource(null, new HashMap<>()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("Config source cannot be null");
        }

        @Test
        @DisplayName("should throw NullPointerException when adding null feature flag")
        void shouldThrowWhenAddingNullFeatureFlag() {
            // Given
            ConfigurationContext context = ConfigurationContext.builder().build();

            // When/Then
            assertThatThrownBy(() -> context.addFeatureFlag(null, new HashMap<>()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("Feature flag cannot be null");
        }

        @Test
        @DisplayName("should throw NullPointerException when adding null secrets manager")
        void shouldThrowWhenAddingNullSecretsManager() {
            // Given
            ConfigurationContext context = ConfigurationContext.builder().build();

            // When/Then
            assertThatThrownBy(() -> context.addSecretsManager(null, new HashMap<>()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("Secrets manager cannot be null");
        }

        @Test
        @DisplayName("should throw NullPointerException when adding null environment")
        void shouldThrowWhenAddingNullEnvironment() {
            // Given
            ConfigurationContext context = ConfigurationContext.builder().build();

            // When/Then
            assertThatThrownBy(() -> context.addEnvironment(null, new HashMap<>()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("Environment cannot be null");
        }

        @Test
        @DisplayName("should throw NullPointerException when adding null validation rule")
        void shouldThrowWhenAddingNullValidationRule() {
            // Given
            ConfigurationContext context = ConfigurationContext.builder().build();

            // When/Then
            assertThatThrownBy(() -> context.addValidationRule(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("Validation rule cannot be null");
        }
    }
}

@Nested
@DisplayName("Setter Tests")
class SetterTests {

    @Test
    @DisplayName("should update service name via setter")
    void shouldUpdateServiceNameViaSetter() {
        // Given
        ConfigurationContext context = ConfigurationContext.builder()
                .serviceName("original-service")
                .build();

        // When
        context.setServiceName("updated-service");

        // Then
        assertThat(context.getServiceName()).isEqualTo("updated-service");
    }

    @Test
    @DisplayName("should update configuration type via setter")
    void shouldUpdateConfigurationTypeViaSetter() {
        // Given
        ConfigurationContext context = ConfigurationContext.builder()
                .configurationType("original-type")
                .build();

        // When
        context.setConfigurationType("updated-type");

        // Then
        assertThat(context.getConfigurationType()).isEqualTo("updated-type");
    }

    @Test
    @DisplayName("should update platform via setter")
    void shouldUpdatePlatformViaSetter() {
        // Given
        ConfigurationContext context = ConfigurationContext.builder()
                .platform("Docker")
                .build();

        // When
        context.setPlatform("Kubernetes");

        // Then
        assertThat(context.getPlatform()).isEqualTo("Kubernetes");
    }

    @Test
    @DisplayName("should set and retrieve service mesh")
    void shouldSetAndRetrieveServiceMesh() {
        // Given
        ConfigurationContext context = ConfigurationContext.builder().build();

        // When
        context.setServiceMesh("Istio");

        // Then
        assertThat(context.getServiceMesh()).isEqualTo("Istio");
    }

    @Test
    @DisplayName("should throw NullPointerException when setting null service mesh")
    void shouldThrowWhenSettingNullServiceMesh() {
        // Given
        ConfigurationContext context = ConfigurationContext.builder().build();

        // When/Then
        assertThatThrownBy(() -> context.setServiceMesh(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Service mesh cannot be null");
    }
}

@Nested
@DisplayName("Config Formats and Validation Tests")
class ConfigFormatsAndValidationTests {

    @Test
    @DisplayName("should add config format")
    void shouldAddConfigFormat() {
        // When
        ConfigurationContext context = ConfigurationContext.builder()
                .addConfigFormat("YAML")
                .addConfigFormat("JSON")
                .addConfigFormat("Properties")
                .build();

        // Then
        assertThat(context.getConfigFormats()).hasSize(3);
        assertThat(context.getConfigFormats()).containsExactlyInAnyOrder("YAML", "JSON", "Properties");
    }

    @Test
    @DisplayName("should add config validation rules")
    void shouldAddConfigValidationRules() {
        // When
        ConfigurationContext context = ConfigurationContext.builder()
                .addConfigValidation("timeout", "30s")
                .addConfigValidation("retry-count", "3")
                .addConfigValidation("batch-size", "100")
                .build();

        // Then
        assertThat(context.getConfigValidation()).hasSize(3);
        assertThat(context.getConfigValidation()).containsEntry("timeout", "30s");
    }

    @Test
    @DisplayName("should throw NullPointerException when adding null config format")
    void shouldThrowWhenAddingNullConfigFormat() {
        // Given
        ConfigurationContext context = ConfigurationContext.builder().build();

        // When/Then
        assertThatThrownBy(() -> context.addConfigFormat(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Config format cannot be null");
    }

    @Test
    @DisplayName("should throw NullPointerException when adding null config validation key")
    void shouldThrowWhenAddingNullValidationKey() {
        // Given
        ConfigurationContext context = ConfigurationContext.builder().build();

        // When/Then
        assertThatThrownBy(() -> context.addConfigValidation(null, "value"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Config validation key cannot be null");
    }

    @Test
    @DisplayName("should throw NullPointerException when adding null config validation value")
    void shouldThrowWhenAddingNullValidationValue() {
        // Given
        ConfigurationContext context = ConfigurationContext.builder().build();

        // When/Then
        assertThatThrownBy(() -> context.addConfigValidation("key", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Config validation value cannot be null");
    }
}

@Nested
@DisplayName("Config Profiles and Mappings Tests")
class ConfigProfilesAndMappingsTests {

    @Test
    @DisplayName("should add config profile")
    void shouldAddConfigProfile() {
        // When
        ConfigurationContext context = ConfigurationContext.builder()
                .addConfigProfile("development")
                .addConfigProfile("production")
                .addConfigProfile("testing")
                .build();

        // Then
        assertThat(context.getConfigProfiles()).hasSize(3);
        assertThat(context.getConfigProfiles()).contains("development", "production", "testing");
    }

    @Test
    @DisplayName("should add profile mapping")
    void shouldAddProfileMapping() {
        // When
        ConfigurationContext context = ConfigurationContext.builder()
                .addConfigProfile("fast")
                .addProfileMapping("fast", "Production")
                .addConfigProfile("slow")
                .addProfileMapping("slow", "Development")
                .build();

        // Then
        assertThat(context.getProfileMappings()).hasSize(2);
        assertThat(context.getProfileMappings()).containsEntry("fast", "Production");
        assertThat(context.getProfileMappings()).containsEntry("slow", "Development");
    }

    @Test
    @DisplayName("should throw NullPointerException when adding null config profile")
    void shouldThrowWhenAddingNullConfigProfile() {
        // Given
        ConfigurationContext context = ConfigurationContext.builder().build();

        // When/Then
        assertThatThrownBy(() -> context.addConfigProfile(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Config profile cannot be null");
    }

    @Test
    @DisplayName("should throw NullPointerException when adding null profile in mapping")
    void shouldThrowWhenAddingNullProfileMapping() {
        // Given
        ConfigurationContext context = ConfigurationContext.builder().build();

        // When/Then
        assertThatThrownBy(() -> context.addProfileMapping(null, "Production"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Profile cannot be null");
    }

    @Test
    @DisplayName("should throw NullPointerException when adding null environment in mapping")
    void shouldThrowWhenAddingNullEnvironmentMapping() {
        // Given
        ConfigurationContext context = ConfigurationContext.builder().build();

        // When/Then
        assertThatThrownBy(() -> context.addProfileMapping("production", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Environment cannot be null");
    }
}

@Nested
@DisplayName("Monitoring, Drift Detection, and Auditing Tests")
class MonitoringDriftAndAuditingTests {

    @Test
    @DisplayName("should add config monitoring")
    void shouldAddConfigMonitoring() {
        // When
        ConfigurationContext context = ConfigurationContext.builder()
                .addConfigMonitoring("Prometheus")
                .addConfigMonitoring("Datadog")
                .addConfigMonitoring("CloudWatch")
                .build();

        // Then
        assertThat(context.getConfigMonitoring()).hasSize(3);
        assertThat(context.getConfigMonitoring()).contains("Prometheus", "Datadog", "CloudWatch");
    }

    @Test
    @DisplayName("should add drift detection")
    void shouldAddDriftDetection() {
        // When
        ConfigurationContext context = ConfigurationContext.builder()
                .addDriftDetection("database-url", "scheduled-check-every-5-minutes")
                .addDriftDetection("cache-size", "real-time-monitoring")
                .build();

        // Then
        assertThat(context.getDriftDetection()).hasSize(2);
        assertThat(context.getDriftDetection()).containsEntry("database-url", "scheduled-check-every-5-minutes");
    }

    @Test
    @DisplayName("should add config auditing")
    void shouldAddConfigAuditing() {
        // When
        ConfigurationContext context = ConfigurationContext.builder()
                .addConfigAuditing("AWS CloudTrail")
                .addConfigAuditing("Kubernetes Audit Logs")
                .addConfigAuditing("ELK Stack")
                .build();

        // Then
        assertThat(context.getConfigAuditing()).hasSize(3);
        assertThat(context.getConfigAuditing()).contains("AWS CloudTrail", "Kubernetes Audit Logs", "ELK Stack");
    }
}

@Nested
@DisplayName("State Validation Tests")
class StateValidationTests {

    @Test
    @DisplayName("should return true for isValid when config sources exist")
    void shouldReturnTrueForValidWithConfigSources() {
        // When
        ConfigurationContext context = ConfigurationContext.builder()
                .addConfigSource("ConfigServer", Map.of("url", "http://config"))
                .build();

        // Then
        assertThat(context.isValid()).isTrue();
    }

    @Test
    @DisplayName("should return true for isValid when feature flags exist")
    void shouldReturnTrueForValidWithFeatureFlags() {
        // When
        ConfigurationContext context = ConfigurationContext.builder()
                .addFeatureFlag("new-feature", Map.of("enabled", true))
                .build();

        // Then
        assertThat(context.isValid()).isTrue();
    }

    @Test
    @DisplayName("should return true for isValid when secrets managers exist")
    void shouldReturnTrueForValidWithSecretsManagers() {
        // When
        ConfigurationContext context = ConfigurationContext.builder()
                .addSecretsManager("Vault", Map.of("url", "http://vault"))
                .build();

        // Then
        assertThat(context.isValid()).isTrue();
    }

    @Test
    @DisplayName("should return false for isValid when empty")
    void shouldReturnFalseForValidWhenEmpty() {
        // When
        ConfigurationContext context = ConfigurationContext.builder().build();

        // Then
        assertThat(context.isValid()).isFalse();
    }

    @Test
    @DisplayName("should return true for hasFeatureFlags when flags and strategies exist")
    void shouldReturnTrueForHasFeatureFlags() {
        // When
        ConfigurationContext context = ConfigurationContext.builder()
                .addFeatureFlag("feature1", Map.of("description", "Feature"))
                .addRolloutStrategy("canary")
                .build();

        // Then
        assertThat(context.hasFeatureFlags()).isTrue();
    }

    @Test
    @DisplayName("should return false for hasFeatureFlags when no strategies")
    void shouldReturnFalseForHasFeaturesWhenNoStrategy() {
        // When
        ConfigurationContext context = ConfigurationContext.builder()
                .addFeatureFlag("feature1", Map.of("description", "Feature"))
                .build();

        // Then
        assertThat(context.hasFeatureFlags()).isFalse();
    }

    @Test
    @DisplayName("should return true for hasSecretsManagement when managers and policies exist")
    void shouldReturnTrueForHasSecretsManagement() {
        // When
        ConfigurationContext context = ConfigurationContext.builder()
                .addSecretsManager("Vault", Map.of("url", "http://vault"))
                .addRotationPolicy("30-days")
                .build();

        // Then
        assertThat(context.hasSecretsManagement()).isTrue();
    }

    @Test
    @DisplayName("should return false for hasSecretsManagement when no policies")
    void shouldReturnFalseForHasSecretsWhenNoPolicy() {
        // When
        ConfigurationContext context = ConfigurationContext.builder()
                .addSecretsManager("Vault", Map.of("url", "http://vault"))
                .build();

        // Then
        assertThat(context.hasSecretsManagement()).isFalse();
    }

    @Test
    @DisplayName("should return true for hasEnvironmentIsolation when 2 or more environments")
    void shouldReturnTrueForHasEnvironmentIsolation() {
        // When
        ConfigurationContext context = ConfigurationContext.builder()
                .addEnvironment("Development", Map.of("replicas", 1))
                .addEnvironment("Production", Map.of("replicas", 5))
                .build();

        // Then
        assertThat(context.hasEnvironmentIsolation()).isTrue();
    }

    @Test
    @DisplayName("should return false for hasEnvironmentIsolation when less than 2 environments")
    void shouldReturnFalseForHasEnvironmentIsolationWithOneEnv() {
        // When
        ConfigurationContext context = ConfigurationContext.builder()
                .addEnvironment("Production", Map.of("replicas", 5))
                .build();

        // Then
        assertThat(context.hasEnvironmentIsolation()).isFalse();
    }

    @Test
    @DisplayName("should return true for hasConfigValidation when validation rules exist")
    void shouldReturnTrueForHasConfigValidation() {
        // When
        ConfigurationContext context = ConfigurationContext.builder()
                .addValidationRule("Timeout Check")
                .build();

        // Then
        assertThat(context.hasConfigValidation()).isTrue();
    }

    @Test
    @DisplayName("should return false for hasConfigValidation when no rules")
    void shouldReturnFalseForHasConfigValidation() {
        // When
        ConfigurationContext context = ConfigurationContext.builder().build();

        // Then
        assertThat(context.hasConfigValidation()).isFalse();
    }
}

@Nested
@DisplayName("Timestamp Update Tests")
class TimestampUpdateTests {

    @Test
    @DisplayName("should update lastUpdated timestamp when adding config source")
    void shouldUpdateTimestampWhenAddingConfigSource() {
        // Given
        ConfigurationContext context = ConfigurationContext.builder().build();
        var initialTimestamp = context.getLastUpdated();

        // When
        context.addConfigSource("NewServer", Map.of("url", "http://new"));

        // Then
        assertThat(context.getLastUpdated()).isAfterOrEqualTo(initialTimestamp);
    }

    @Test
    @DisplayName("should update lastUpdated timestamp when setting service name")
    void shouldUpdateTimestampWhenSettingServiceName() {
        // Given
        ConfigurationContext context = ConfigurationContext.builder().build();
        var initialTimestamp = context.getLastUpdated();

        // When
        context.setServiceName("new-service");

        // Then
        assertThat(context.getLastUpdated()).isAfterOrEqualTo(initialTimestamp);
    }
}

@Nested
@DisplayName("toString Tests")
class ToStringTests {

    @Test
    @DisplayName("should contain service name in toString output")
    void shouldContainServiceNameInToString() {
        // When
        ConfigurationContext context = ConfigurationContext.builder()
                .serviceName("test-service")
                .build();

        // Then
        assertThat(context.toString()).contains("test-service");
    }

    @Test
    @DisplayName("should contain configuration type in toString output")
    void shouldContainConfigTypeInToString() {
        // When
        ConfigurationContext context = ConfigurationContext.builder()
                .configurationType("microservices")
                .build();

        // Then
        assertThat(context.toString()).contains("microservices");
    }

    @Test
    @DisplayName("should contain configuration counts in toString output")
    void shouldContainCountsInToString() {
        // When
        ConfigurationContext context = ConfigurationContext.builder()
                .addConfigSource("Server1", Map.of("url", "http://server1"))
                .addFeatureFlag("flag1", Map.of("enabled", true))
                .addSecretsManager("Vault", Map.of("url", "http://vault"))
                .build();

        // Then
        String output = context.toString();
        assertThat(output).contains("sources=1");
        assertThat(output).contains("flags=1");
        assertThat(output).contains("secrets=1");
    }
}

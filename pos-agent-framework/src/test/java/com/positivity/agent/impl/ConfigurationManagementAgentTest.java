package com.positivity.agent.impl;

import com.positivity.agent.AgentConsultationRequest;
import com.positivity.agent.AgentGuidanceResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConfigurationManagementAgent to verify configuration
 * management guidance capabilities
 * Validates: Requirements REQ-014.1, REQ-014.2, REQ-014.3, REQ-014.4, REQ-014.5
 */
class ConfigurationManagementAgentTest {

    private ConfigurationManagementAgent configAgent;

    @BeforeEach
    void setUp() {
        configAgent = new ConfigurationManagementAgent();
    }

    @Test
    void testSpringCloudConfigGuidance() throws ExecutionException, InterruptedException {
        // Test Spring Cloud Config Server configuration guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "configuration",
                "How to configure Spring Cloud Config Server with spring cloud config centralized configuration?",
                Map.of("service", "pos-api-gateway"));

        CompletableFuture<AgentGuidanceResponse> future = configAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("Spring Cloud Config Server"));
        assertTrue(response.guidance().contains("@EnableConfigServer"));
        assertTrue(response.guidance().contains("spring-cloud-config-server"));
        assertTrue(response.guidance().contains("bootstrap.yml"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testConsulConfigurationGuidance() throws ExecutionException, InterruptedException {
        // Test Consul configuration management guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "configuration",
                "How to integrate Consul for configuration management with consul configuration service discovery?",
                Map.of("service", "pos-service-discovery"));

        CompletableFuture<AgentGuidanceResponse> future = configAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("Consul Configuration Management"));
        assertTrue(response.guidance().contains("spring-cloud-starter-consul"));
        assertTrue(response.guidance().contains("consul.host"));
        assertTrue(response.guidance().contains("key-value store"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testEtcdConfigurationGuidance() throws ExecutionException, InterruptedException {
        // Test etcd configuration management guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "configuration",
                "How to use etcd for distributed configuration with etcd distributed configuration kubernetes?",
                Map.of("service", "pos-catalog"));

        CompletableFuture<AgentGuidanceResponse> future = configAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("etcd Configuration Management"));
        assertTrue(response.guidance().contains("distributed key-value"));
        assertTrue(response.guidance().contains("Kubernetes"));
        assertTrue(response.guidance().contains("etcd client"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testFeatureFlagsGuidance() throws ExecutionException, InterruptedException {
        // Test feature flags and gradual rollout guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "configuration",
                "How to implement feature flags for gradual rollouts with feature flags toggle gradual rollout?",
                Map.of("service", "pos-order"));

        CompletableFuture<AgentGuidanceResponse> future = configAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("Feature Flags Implementation"));
        assertTrue(response.guidance().contains("@ConditionalOnProperty"));
        assertTrue(response.guidance().contains("gradual rollout"));
        assertTrue(response.guidance().contains("percentage-based"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testAWSSecretsManagerGuidance() throws ExecutionException, InterruptedException {
        // Test AWS Secrets Manager integration guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "configuration",
                "How to integrate AWS Secrets Manager for secrets management with aws secrets manager spring cloud?",
                Map.of("service", "pos-security-service"));

        CompletableFuture<AgentGuidanceResponse> future = configAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("AWS Secrets Manager Integration"));
        assertTrue(response.guidance().contains("spring-cloud-aws-secrets"));
        assertTrue(response.guidance().contains("SecretsManagerClient"));
        assertTrue(response.guidance().contains("rotation"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testHashiCorpVaultGuidance() throws ExecutionException, InterruptedException {
        // Test HashiCorp Vault integration guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "configuration",
                "How to integrate HashiCorp Vault for secrets management with vault secrets management spring vault?",
                Map.of("service", "pos-customer"));

        CompletableFuture<AgentGuidanceResponse> future = configAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("HashiCorp Vault Integration"));
        assertTrue(response.guidance().contains("spring-cloud-vault"));
        assertTrue(response.guidance().contains("VaultTemplate"));
        assertTrue(response.guidance().contains("dynamic secrets"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testKubernetesSecretsGuidance() throws ExecutionException, InterruptedException {
        // Test Kubernetes Secrets and ConfigMaps guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "configuration",
                "How to use Kubernetes Secrets and ConfigMaps with kubernetes secrets configmaps spring boot?",
                Map.of("service", "pos-inventory"));

        CompletableFuture<AgentGuidanceResponse> future = configAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("Kubernetes Secrets and ConfigMaps"));
        assertTrue(response.guidance().contains("spring-cloud-kubernetes"));
        assertTrue(response.guidance().contains("kubectl create secret"));
        assertTrue(response.guidance().contains("volumeMounts"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testEnvironmentSpecificConfigGuidance() throws ExecutionException, InterruptedException {
        // Test environment-specific configuration guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "configuration",
                "How to manage environment-specific configurations with environment configuration profiles spring?",
                Map.of("service", "pos-price"));

        CompletableFuture<AgentGuidanceResponse> future = configAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("Environment-Specific Configuration"));
        assertTrue(response.guidance().contains("spring.profiles.active"));
        assertTrue(response.guidance().contains("application-{profile}.yml"));
        assertTrue(response.guidance().contains("@Profile"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testConfigurationValidationGuidance() throws ExecutionException, InterruptedException {
        // Test configuration validation and health checks guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "configuration",
                "How to validate configuration and implement health checks with configuration validation health checks?",
                Map.of("service", "pos-accounting"));

        CompletableFuture<AgentGuidanceResponse> future = configAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("Configuration Validation"));
        assertTrue(response.guidance().contains("@ConfigurationProperties"));
        assertTrue(response.guidance().contains("@Validated"));
        assertTrue(response.guidance().contains("HealthIndicator"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testAgentCapabilities() {
        // Verify agent capabilities
        assertTrue(configAgent.getCapabilities().contains("spring-cloud-config"));
        assertTrue(configAgent.getCapabilities().contains("consul"));
        assertTrue(configAgent.getCapabilities().contains("etcd"));
        assertTrue(configAgent.getCapabilities().contains("feature-flags"));
        assertTrue(configAgent.getCapabilities().contains("aws-secrets-manager"));
        assertTrue(configAgent.getCapabilities().contains("hashicorp-vault"));
        assertTrue(configAgent.getCapabilities().contains("kubernetes-secrets"));
        assertTrue(configAgent.getCapabilities().contains("environment-configs"));
        assertTrue(configAgent.getCapabilities().contains("config-validation"));
        assertTrue(configAgent.getCapabilities().contains("centralized-config"));
    }

    @Test
    void testCanHandleConfigurationRequests() {
        // Test that agent can handle configuration domain requests
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "configuration",
                "General configuration management question with configuration management patterns",
                Map.of());

        assertTrue(configAgent.canHandle(request));
    }

    @Test
    void testCanHandleCapabilityBasedRequests() {
        // Test that agent can handle requests based on capabilities
        AgentConsultationRequest vaultRequest = AgentConsultationRequest.create(
                "security",
                "I need Vault secrets management guidance with vault secrets management",
                Map.of());

        assertTrue(configAgent.canHandle(vaultRequest));

        AgentConsultationRequest featureFlagsRequest = AgentConsultationRequest.create(
                "deployment",
                "Feature flags implementation question with feature flags toggle",
                Map.of());

        assertTrue(configAgent.canHandle(featureFlagsRequest));
    }

    @Test
    void testAgentMetadata() {
        // Verify agent metadata
        assertEquals("configuration-management-agent", configAgent.getId());
        assertEquals("Configuration Management Agent", configAgent.getName());
        assertEquals("configuration", configAgent.getDomain());
        assertTrue(configAgent.getDependencies().contains("security-agent"));
        assertTrue(configAgent.getDependencies().contains("deployment-agent"));
    }
}
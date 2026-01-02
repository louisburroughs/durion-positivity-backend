package com.pos.agent.impl;

import com.pos.agent.core.AgentManager;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.context.AgentContext;
import com.pos.agent.context.ConfigurationContext;
import com.pos.agent.core.SecurityContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for configuration management agent guidance using core API.
 * Tests centralized config, secrets management, and environment configs.
 * Validates: Requirements REQ-014.1, REQ-014.2, REQ-014.3, REQ-014.4, REQ-014.5
 */
class ConfigurationManagementAgentTest {

    private final AgentManager agentManager = new AgentManager();
    private final SecurityContext securityContext = SecurityContext.builder()
            .jwtToken("valid-jwt-token-for-tests")
            .userId("config-agent-tester")
            .roles(List.of("CONFIG_MANAGER", "SECURITY_SPECIALIST"))
            .permissions(List.of("AGENT_READ", "AGENT_WRITE","config.manage", "secrets.access"))
            .serviceId("pos-config-agent-tests")
            .serviceType("test")
            .build();

    @Test
    void testSpringCloudConfigGuidance() {
        AgentContext context = ConfigurationContext.builder()
                
                .property("service", "pos-api-gateway")
                .property("topic", "spring-cloud-config")
                .property("query", "How to configure Spring Cloud Config Server?")
                .property("keywords", "spring cloud config centralized configuration")
                .build();

        AgentRequest request = AgentRequest.builder()
                .type("configuration")
                .description("Spring Cloud Config guidance for pos-api-gateway service")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Test
    void testConsulConfigurationGuidance() {
        AgentContext context = ConfigurationContext.builder()
                
                .property("service", "pos-service-discovery")
                .property("topic", "consul")
                .property("query", "How to integrate Consul for configuration management?")
                .property("keywords", "consul configuration service discovery")
                .build();

        AgentRequest request = AgentRequest.builder()
                .type("configuration")
                .description("Consul configuration guidance for pos-service-discovery service")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Test
    void testEtcdConfigurationGuidance() {
        AgentContext context = ConfigurationContext.builder()
                
                .property("service", "pos-catalog")
                .property("topic", "etcd")
                .property("query", "How to use etcd for distributed configuration?")
                .property("keywords", "etcd distributed configuration kubernetes")
                .build();

        AgentRequest request = AgentRequest.builder()
                .type("configuration")
                .description("Etcd configuration guidance for pos-catalog service")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Test
    void testFeatureFlagsGuidance() {
        AgentContext context = ConfigurationContext.builder()
                
                .property("service", "pos-order")
                .property("topic", "feature-flags")
                .property("query", "How to implement feature flags for gradual rollouts?")
                .property("keywords", "feature flags toggle gradual rollout")
                .build();

        AgentRequest request = AgentRequest.builder()
                .type("configuration")
                .description("Feature flags guidance for pos-order service")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Test
    void testAWSSecretsManagerGuidance() {
        AgentContext context = ConfigurationContext.builder()
                
                .property("service", "pos-security-service")
                .property("topic", "aws-secrets-manager")
                .property("query", "How to integrate AWS Secrets Manager for secrets management?")
                .property("keywords", "aws secrets manager spring cloud")
                .build();

        AgentRequest request = AgentRequest.builder()
                .type("configuration")
                .description("AWS Secrets Manager guidance for pos-security-service")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Test
    void testHashiCorpVaultGuidance() {
        AgentContext context = ConfigurationContext.builder()
                
                .property("service", "pos-customer")
                .property("topic", "hashicorp-vault")
                .property("query", "How to integrate HashiCorp Vault for secrets management?")
                .property("keywords", "vault secrets management spring vault")
                .build();

        AgentRequest request = AgentRequest.builder()
                .type("configuration")
                .description("HashiCorp Vault guidance for pos-customer service")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Test
    void testKubernetesSecretsGuidance() {
        AgentContext context = ConfigurationContext.builder()
                
                .property("service", "pos-inventory")
                .property("topic", "kubernetes-secrets")
                .property("query", "How to use Kubernetes Secrets and ConfigMaps?")
                .property("keywords", "kubernetes secrets configmaps spring boot")
                .build();

        AgentRequest request = AgentRequest.builder()
                .type("configuration")
                .description("Kubernetes Secrets guidance for pos-inventory service")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Test
    void testEnvironmentSpecificConfigGuidance() {
        AgentContext context = ConfigurationContext.builder()
                
                .property("service", "pos-price")
                .property("topic", "environment-config")
                .property("query", "How to manage environment-specific configurations?")
                .property("keywords", "environment configuration profiles spring")
                .build();

        AgentRequest request = AgentRequest.builder()
                .type("configuration")
                .description("Environment specific configuration guidance for pos-price service")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Test
    void testConfigurationValidationGuidance() {
        AgentContext context = ConfigurationContext.builder()
                
                .property("service", "pos-accounting")
                .property("topic", "validation")
                .property("query", "How to validate configuration and implement health checks?")
                .property("keywords", "configuration validation health checks")
                .build();

        AgentRequest request = AgentRequest.builder()
                .type("configuration")
                .description("Configuration validation guidance for pos-accounting service")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }
}
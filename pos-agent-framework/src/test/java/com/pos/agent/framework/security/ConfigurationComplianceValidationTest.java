package com.pos.agent.framework.security;

import com.pos.agent.framework.core.AgentManager;
import com.pos.agent.framework.core.AgentRequest;
import com.pos.agent.framework.core.AgentResponse;
import com.pos.agent.framework.core.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Configuration management compliance validation tests.
 * Tests secure configuration handling, secrets management, and environment isolation.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "pos.agent.framework.security.enabled=true",
    "pos.agent.framework.config.validation=true",
    "pos.agent.framework.secrets.encryption=true"
})
class ConfigurationComplianceValidationTest {

    @Autowired
    private AgentManager agentManager;

    private SecurityContext configManagerContext;
    private SecurityContext developerContext;

    @BeforeEach
    void setUp() {
        configManagerContext = SecurityContext.builder()
            .jwtToken("config.manager.jwt.token")
            .userId("config-manager")
            .roles(List.of("CONFIG_MANAGER", "ADMIN"))
            .permissions(List.of("AGENT_READ", "AGENT_WRITE", "CONFIG_MANAGE", "SECRETS_MANAGE"))
            .build();

        developerContext = SecurityContext.builder()
            .jwtToken("developer.jwt.token")
            .userId("developer")
            .roles(List.of("DEVELOPER"))
            .permissions(List.of("AGENT_READ", "AGENT_WRITE"))
            .build();
    }

    @Test
    void testSpringCloudConfigCompliance() {
        AgentRequest configRequest = AgentRequest.builder()
            .type("configuration-management")
            .securityContext(configManagerContext)
            .requestData(Map.of(
                "configType", "spring-cloud-config",
                "environment", "production",
                "service", "pos-inventory"
            ))
            .build();

        assertDoesNotThrow(() -> {
            AgentResponse response = agentManager.processRequest(configRequest);
            assertNotNull(response);
            assertTrue(response.isSuccess());
            
            Map<String, Object> responseData = response.getResponseData();
            assertTrue(responseData.containsKey("configServerUrl"));
            assertTrue(responseData.containsKey("encryptionEnabled"));
            assertEquals(true, responseData.get("encryptionEnabled"));
        });
    }

    @Test
    void testSecretsManagerIntegration() {
        // Test AWS Secrets Manager integration
        AgentRequest awsSecretsRequest = AgentRequest.builder()
            .type("configuration-management")
            .securityContext(configManagerContext)
            .requestData(Map.of(
                "configType", "secrets",
                "secretsManager", "aws-secrets-manager",
                "region", "us-east-1",
                "secretName", "pos/database/credentials"
            ))
            .build();

        assertDoesNotThrow(() -> {
            AgentResponse response = agentManager.processRequest(awsSecretsRequest);
            assertNotNull(response);
            assertTrue(response.isSuccess());
            
            Map<String, Object> responseData = response.getResponseData();
            assertTrue(responseData.containsKey("secretsManagerConfig"));
            assertTrue(responseData.containsKey("encryptionAtRest"));
            assertTrue(responseData.containsKey("encryptionInTransit"));
            assertEquals(true, responseData.get("encryptionAtRest"));
            assertEquals(true, responseData.get("encryptionInTransit"));
        });

        // Test HashiCorp Vault integration
        AgentRequest vaultRequest = AgentRequest.builder()
            .type("configuration-management")
            .securityContext(configManagerContext)
            .requestData(Map.of(
                "configType", "secrets",
                "secretsManager", "hashicorp-vault",
                "vaultUrl", "https://vault.example.com",
                "secretPath", "secret/pos/database"
            ))
            .build();

        assertDoesNotThrow(() -> {
            AgentResponse response = agentManager.processRequest(vaultRequest);
            assertNotNull(response);
            assertTrue(response.isSuccess());
            
            Map<String, Object> responseData = response.getResponseData();
            assertTrue(responseData.containsKey("vaultConfig"));
            assertTrue(responseData.containsKey("tokenAuthentication"));
            assertTrue(responseData.containsKey("sealStatus"));
        });
    }

    @Test
    void testEnvironmentIsolationCompliance() {
        // Test production environment configuration
        AgentRequest prodRequest = AgentRequest.builder()
            .type("configuration-management")
            .securityContext(configManagerContext)
            .requestData(Map.of(
                "configType", "environment",
                "environment", "production",
                "service", "pos-payment"
            ))
            .build();

        assertDoesNotThrow(() -> {
            AgentResponse response = agentManager.processRequest(prodRequest);
            assertNotNull(response);
            assertTrue(response.isSuccess());
            
            Map<String, Object> responseData = response.getResponseData();
            assertTrue(responseData.containsKey("environmentIsolation"));
            assertTrue(responseData.containsKey("configEncryption"));
            assertEquals("production", responseData.get("environment"));
            assertEquals(true, responseData.get("configEncryption"));
        });

        // Test development environment configuration
        AgentRequest devRequest = AgentRequest.builder()
            .type("configuration-management")
            .securityContext(developerContext)
            .requestData(Map.of(
                "configType", "environment",
                "environment", "development",
                "service", "pos-payment"
            ))
            .build();

        assertDoesNotThrow(() -> {
            AgentResponse response = agentManager.processRequest(devRequest);
            assertNotNull(response);
            assertTrue(response.isSuccess());
            
            Map<String, Object> responseData = response.getResponseData();
            assertEquals("development", responseData.get("environment"));
            // Development can have less strict encryption requirements
            assertTrue(responseData.containsKey("configEncryption"));
        });
    }

    @Test
    void testFeatureFlagCompliance() {
        AgentRequest featureFlagRequest = AgentRequest.builder()
            .type("configuration-management")
            .securityContext(configManagerContext)
            .requestData(Map.of(
                "configType", "feature-flags",
                "flagProvider", "aws-appconfig",
                "application", "pos-system",
                "environment", "production"
            ))
            .build();

        assertDoesNotThrow(() -> {
            AgentResponse response = agentManager.processRequest(featureFlagRequest);
            assertNotNull(response);
            assertTrue(response.isSuccess());
            
            Map<String, Object> responseData = response.getResponseData();
            assertTrue(responseData.containsKey("featureFlagConfig"));
            assertTrue(responseData.containsKey("gradualRollout"));
            assertTrue(responseData.containsKey("auditLogging"));
            assertEquals(true, responseData.get("auditLogging"));
        });
    }

    @Test
    void testUnauthorizedConfigurationAccess() {
        // Test developer trying to access production secrets
        AgentRequest unauthorizedRequest = AgentRequest.builder()
            .type("configuration-management")
            .securityContext(developerContext) // No SECRETS_MANAGE permission
            .requestData(Map.of(
                "configType", "secrets",
                "secretsManager", "aws-secrets-manager",
                "environment", "production"
            ))
            .build();

        assertThrows(SecurityException.class, () -> {
            agentManager.processRequest(unauthorizedRequest);
        });
    }

    @Test
    void testConfigurationAuditTrail() {
        // Make configuration changes to generate audit trail
        List<Map<String, Object>> configRequests = List.of(
            Map.of("configType", "spring-cloud-config", "environment", "staging"),
            Map.of("configType", "secrets", "secretsManager", "aws-secrets-manager"),
            Map.of("configType", "feature-flags", "flagProvider", "aws-appconfig")
        );

        for (Map<String, Object> requestData : configRequests) {
            AgentRequest request = AgentRequest.builder()
                .type("configuration-management")
                .securityContext(configManagerContext)
                .requestData(requestData)
                .build();

            assertDoesNotThrow(() -> {
                AgentResponse response = agentManager.processRequest(request);
                assertNotNull(response);
                assertTrue(response.isSuccess());
            });
        }

        // Verify audit trail contains configuration changes
        var auditEntries = agentManager.getAuditTrail(configManagerContext.getUserId());
        assertNotNull(auditEntries);
        assertTrue(auditEntries.size() >= 3);
        
        // Verify audit entries contain configuration-specific information
        boolean hasConfigAudit = auditEntries.stream()
            .anyMatch(entry -> "configuration-management".equals(entry.getAgentType()));
        assertTrue(hasConfigAudit);
    }

    @Test
    void testConfigurationEncryptionCompliance() {
        AgentRequest encryptionRequest = AgentRequest.builder()
            .type("configuration-management")
            .securityContext(configManagerContext)
            .requestData(Map.of(
                "configType", "encryption",
                "encryptionProvider", "aws-kms",
                "keyId", "arn:aws:kms:us-east-1:123456789012:key/12345678-1234-1234-1234-123456789012"
            ))
            .build();

        assertDoesNotThrow(() -> {
            AgentResponse response = agentManager.processRequest(encryptionRequest);
            assertNotNull(response);
            assertTrue(response.isSuccess());
            
            Map<String, Object> responseData = response.getResponseData();
            assertTrue(responseData.containsKey("encryptionConfig"));
            assertTrue(responseData.containsKey("keyRotation"));
            assertTrue(responseData.containsKey("encryptionAlgorithm"));
            assertEquals("AES-256-GCM", responseData.get("encryptionAlgorithm"));
        });
    }
}

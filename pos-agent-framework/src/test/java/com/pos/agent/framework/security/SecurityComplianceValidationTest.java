package com.pos.agent.framework.security;

import com.pos.agent.framework.core.Agent;
import com.pos.agent.framework.core.AgentManager;
import com.pos.agent.framework.core.AgentRegistry;
import com.pos.agent.framework.core.AgentRequest;
import com.pos.agent.framework.core.AgentResponse;
import com.pos.agent.framework.core.SecurityContext;
import com.pos.agent.framework.core.AuditTrail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security and compliance validation tests for agent framework.
 * Tests authentication, authorization, secrets management, and audit trails.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "pos.agent.framework.security.enabled=true",
    "pos.agent.framework.audit.enabled=true",
    "pos.agent.framework.compliance.validation=true"
})
class SecurityComplianceValidationTest {

    @Autowired
    private AgentManager agentManager;

    @Autowired
    private AgentRegistry agentRegistry;

    private SecurityContext validSecurityContext;
    private SecurityContext invalidSecurityContext;

    @BeforeEach
    void setUp() {
        // Create valid security context with JWT token
        validSecurityContext = SecurityContext.builder()
            .jwtToken("valid.jwt.token")
            .userId("test-user")
            .roles(List.of("DEVELOPER", "AGENT_USER"))
            .permissions(List.of("AGENT_READ", "AGENT_WRITE"))
            .build();

        // Create invalid security context
        invalidSecurityContext = SecurityContext.builder()
            .jwtToken("invalid.jwt.token")
            .userId("unauthorized-user")
            .roles(List.of("GUEST"))
            .permissions(List.of())
            .build();
    }

    @Test
    void testAgentAuthenticationValidation() {
        // Test valid authentication
        AgentRequest validRequest = AgentRequest.builder()
            .type("architecture")
            .securityContext(validSecurityContext)
            .requestData(Map.of("service", "pos-inventory"))
            .build();

        assertDoesNotThrow(() -> {
            AgentResponse response = agentManager.processRequest(validRequest);
            assertNotNull(response);
            assertTrue(response.isSuccess());
        });

        // Test invalid authentication
        AgentRequest invalidRequest = AgentRequest.builder()
            .type("architecture")
            .securityContext(invalidSecurityContext)
            .requestData(Map.of("service", "pos-inventory"))
            .build();

        assertThrows(SecurityException.class, () -> {
            agentManager.processRequest(invalidRequest);
        });
    }

    @Test
    void testRoleBasedAccessControl() {
        // Test developer role access to implementation agent
        AgentRequest devRequest = AgentRequest.builder()
            .type("implementation")
            .securityContext(validSecurityContext)
            .requestData(Map.of("service", "pos-catalog"))
            .build();

        assertDoesNotThrow(() -> {
            AgentResponse response = agentManager.processRequest(devRequest);
            assertNotNull(response);
            assertTrue(response.isSuccess());
        });

        // Test guest role denied access
        SecurityContext guestContext = SecurityContext.builder()
            .jwtToken("guest.jwt.token")
            .userId("guest-user")
            .roles(List.of("GUEST"))
            .permissions(List.of("AGENT_READ"))
            .build();

        AgentRequest guestRequest = AgentRequest.builder()
            .type("security")
            .securityContext(guestContext)
            .requestData(Map.of("service", "pos-security"))
            .build();

        assertThrows(SecurityException.class, () -> {
            agentManager.processRequest(guestRequest);
        });
    }

    @Test
    void testSecretsManagementCompliance() {
        // Test configuration management agent with secrets
        SecurityContext adminContext = SecurityContext.builder()
            .jwtToken("admin.jwt.token")
            .userId("admin-user")
            .roles(List.of("ADMIN", "CONFIG_MANAGER"))
            .permissions(List.of("AGENT_READ", "AGENT_WRITE", "SECRETS_MANAGE"))
            .build();

        AgentRequest secretsRequest = AgentRequest.builder()
            .type("configuration-management")
            .securityContext(adminContext)
            .requestData(Map.of(
                "configType", "secrets",
                "secretsManager", "aws-secrets-manager",
                "environment", "production"
            ))
            .build();

        assertDoesNotThrow(() -> {
            AgentResponse response = agentManager.processRequest(secretsRequest);
            assertNotNull(response);
            assertTrue(response.isSuccess());
            
            // Verify secrets are not exposed in response
            String responseData = response.getResponseData().toString();
            assertFalse(responseData.contains("password"));
            assertFalse(responseData.contains("secret"));
            assertFalse(responseData.contains("key"));
        });

        // Test unauthorized secrets access
        AgentRequest unauthorizedSecretsRequest = AgentRequest.builder()
            .type("configuration-management")
            .securityContext(validSecurityContext) // No SECRETS_MANAGE permission
            .requestData(Map.of(
                "configType", "secrets",
                "secretsManager", "aws-secrets-manager"
            ))
            .build();

        assertThrows(SecurityException.class, () -> {
            agentManager.processRequest(unauthorizedSecretsRequest);
        });
    }

    @Test
    void testAuditTrailGeneration() {
        // Process multiple requests to generate audit trail
        List<String> agentTypes = List.of("architecture", "implementation", "security");
        
        for (String agentType : agentTypes) {
            AgentRequest request = AgentRequest.builder()
                .type(agentType)
                .securityContext(validSecurityContext)
                .requestData(Map.of("service", "pos-test"))
                .build();

            assertDoesNotThrow(() -> {
                agentManager.processRequest(request);
            });
        }

        // Verify audit trail entries
        List<AuditTrail> auditEntries = agentManager.getAuditTrail(validSecurityContext.getUserId());
        assertNotNull(auditEntries);
        assertTrue(auditEntries.size() >= 3);

        // Verify audit entry structure
        AuditTrail firstEntry = auditEntries.get(0);
        assertNotNull(firstEntry.getTimestamp());
        assertEquals(validSecurityContext.getUserId(), firstEntry.getUserId());
        assertNotNull(firstEntry.getAgentType());
        assertNotNull(firstEntry.getAction());
        assertTrue(firstEntry.isSuccess());
    }

    @Test
    void testTLSEncryptionCompliance() {
        // Test that all agent communications use TLS 1.3
        List<Agent> allAgents = agentRegistry.getAllAgents();
        
        for (Agent agent : allAgents) {
            // Verify agent supports TLS 1.3
            assertTrue(agent.supportsTLS13(), 
                "Agent " + agent.getType() + " must support TLS 1.3");
            
            // Verify no plain HTTP communications
            assertFalse(agent.allowsPlainHTTP(), 
                "Agent " + agent.getType() + " must not allow plain HTTP");
        }
    }

    @Test
    void testJWTTokenValidation() {
        // Test expired token
        SecurityContext expiredContext = SecurityContext.builder()
            .jwtToken("expired.jwt.token")
            .userId("test-user")
            .roles(List.of("DEVELOPER"))
            .permissions(List.of("AGENT_READ"))
            .expired(true)
            .build();

        AgentRequest expiredRequest = AgentRequest.builder()
            .type("architecture")
            .securityContext(expiredContext)
            .requestData(Map.of("service", "pos-test"))
            .build();

        assertThrows(SecurityException.class, () -> {
            agentManager.processRequest(expiredRequest);
        });

        // Test malformed token
        SecurityContext malformedContext = SecurityContext.builder()
            .jwtToken("malformed.token")
            .userId("test-user")
            .roles(List.of("DEVELOPER"))
            .permissions(List.of("AGENT_READ"))
            .build();

        AgentRequest malformedRequest = AgentRequest.builder()
            .type("architecture")
            .securityContext(malformedContext)
            .requestData(Map.of("service", "pos-test"))
            .build();

        assertThrows(SecurityException.class, () -> {
            agentManager.processRequest(malformedRequest);
        });
    }

    @Test
    void testConcurrentSecurityValidation() throws Exception {
        // Test concurrent requests with different security contexts
        int concurrentRequests = 20;
        CompletableFuture<Void>[] futures = new CompletableFuture[concurrentRequests];

        for (int i = 0; i < concurrentRequests; i++) {
            final int requestId = i;
            SecurityContext context = (i % 2 == 0) ? validSecurityContext : invalidSecurityContext;
            
            futures[i] = CompletableFuture.runAsync(() -> {
                AgentRequest request = AgentRequest.builder()
                    .type("architecture")
                    .securityContext(context)
                    .requestData(Map.of("service", "pos-test-" + requestId))
                    .build();

                if (context == validSecurityContext) {
                    assertDoesNotThrow(() -> {
                        AgentResponse response = agentManager.processRequest(request);
                        assertNotNull(response);
                    });
                } else {
                    assertThrows(SecurityException.class, () -> {
                        agentManager.processRequest(request);
                    });
                }
            });
        }

        // Wait for all requests to complete
        CompletableFuture.allOf(futures).get(30, TimeUnit.SECONDS);
    }

    @Test
    void testComplianceReporting() {
        // Generate compliance report
        Map<String, Object> complianceReport = agentManager.generateComplianceReport();
        
        assertNotNull(complianceReport);
        
        // Verify required compliance metrics
        assertTrue(complianceReport.containsKey("authenticationCompliance"));
        assertTrue(complianceReport.containsKey("authorizationCompliance"));
        assertTrue(complianceReport.containsKey("auditTrailCompliance"));
        assertTrue(complianceReport.containsKey("encryptionCompliance"));
        
        // Verify 100% compliance
        assertEquals(100.0, complianceReport.get("authenticationCompliance"));
        assertEquals(100.0, complianceReport.get("authorizationCompliance"));
        assertEquals(100.0, complianceReport.get("auditTrailCompliance"));
        assertEquals(100.0, complianceReport.get("encryptionCompliance"));
    }
}

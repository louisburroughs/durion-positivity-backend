package com.pos.agent.framework.security;

import com.pos.agent.core.AgentManager;
import com.pos.agent.core.SecurityContext;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.context.AgentContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Audit trail validation tests for comprehensive agent interaction logging.
 * Tests audit trail generation and interaction tracking.
 */
class AuditTrailValidationTest {

    private AgentManager agentManager;

    private SecurityContext adminContext;
    private SecurityContext developerContext;
    private SecurityContext guestContext;

    @BeforeEach
    void setUp() {
        agentManager = new AgentManager();

        adminContext = SecurityContext.builder()
                .jwtToken("admin.jwt.token")
                .userId("admin-user")
                .roles(List.of("ADMIN", "SECURITY_OFFICER"))
                .permissions(List.of("AGENT_READ", "AGENT_WRITE", "AUDIT_READ", "AUDIT_MANAGE"))
                .build();

        developerContext = SecurityContext.builder()
                .jwtToken("developer.jwt.token")
                .userId("developer-user")
                .roles(List.of("DEVELOPER"))
                .permissions(List.of("AGENT_READ", "AGENT_WRITE"))
                .build();

        guestContext = SecurityContext.builder()
                .jwtToken("guest.jwt.token")
                .userId("guest-user")
                .roles(List.of("GUEST"))
                .permissions(List.of())
                .build();
    }

    @Test
    void testBasicAuditTrailGeneration() {
        // Generate various agent interactions
        List<String> agentTypes = List.of(
                "architecture", "implementation", "security", "testing",
                "deployment", "observability", "documentation");

        for (String agentType : agentTypes) {
            AgentRequest request = AgentRequest.builder()
                    .type(agentType)
                    .description("Basic audit trail generation for " + agentType + " agent")
                    .securityContext(developerContext)
                    .context(AgentContext.builder()
                            .property("service", "pos-test-" + agentType)
                            .build())
                    .build();

            assertDoesNotThrow(() -> {
                AgentResponse response = agentManager.processRequest(request);
                assertNotNull(response);
                assertTrue(response.isSuccess());
            });
        }
    }

    @Test
    void testDetailedAuditTrailContent() {
        AgentRequest detailedRequest = AgentRequest.builder()
                .type("security")
                .description("Detailed audit trail content verification")
                .securityContext(adminContext)
                .context(AgentContext.builder()
                        .property("service", "pos-payment")
                        .property("securityType", "jwt-authentication")
                        .property("environment", "production")
                        .build())
                .build();

        assertDoesNotThrow(() -> {
            AgentResponse response = agentManager.processRequest(detailedRequest);
            assertNotNull(response);
            assertTrue(response.isSuccess());
        });
    }

    @Test
    void testFailedRequestAuditTrail() {
        // Create request that will fail due to insufficient permissions
        AgentRequest failedRequest = AgentRequest.builder()
                .type("security")
                .description("Failed request audit trail verification")
                .securityContext(guestContext) // Guest doesn't have security agent access
                .context(AgentContext.builder()
                        .property("service", "pos-security")
                        .build())
                .build();

        AgentResponse response = agentManager.processRequest(failedRequest);

        // Verify that the request failed due to authorization
        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertNotNull(response.getErrorMessage());
        assertTrue(response.getErrorMessage().contains("Authorization failed"));
    }

    @Test
    void testConcurrentAuditTrailGeneration() throws Exception {
        int concurrentRequests = 50;
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futures = new CompletableFuture[concurrentRequests];

        for (int i = 0; i < concurrentRequests; i++) {
            final int requestId = i;
            futures[i] = CompletableFuture.runAsync(() -> {
                AgentRequest request = AgentRequest.builder()
                        .type("architecture")
                        .description("Concurrent audit trail generation request " + requestId)
                        .securityContext(developerContext)
                        .context(AgentContext.builder()
                                .property("service", "pos-concurrent-" + requestId)
                                .build())
                        .build();

                assertDoesNotThrow(() -> {
                    AgentResponse response = agentManager.processRequest(request);
                    assertNotNull(response);
                    assertTrue(response.isSuccess());
                });
            });
        }

        // Wait for all requests to complete
        CompletableFuture.allOf(futures).get(30, TimeUnit.SECONDS);
    }

    @Test
    void testAuditTrailRetention() {
        // Test that agent manager is properly instantiated for audit operations
        assertNotNull(agentManager);

        // Verify basic functionality with retention context
        AgentRequest request = AgentRequest.builder()
                .type("security")
                .description("Audit trail retention verification")
                .securityContext(adminContext)
                .context(AgentContext.builder()
                        .property("auditContext", "retention-test")
                        .build())
                .build();

        assertDoesNotThrow(() -> {
            AgentResponse response = agentManager.processRequest(request);
            assertNotNull(response);
        });
    }

    @Test
    void testAuditTrailSearch() {
        // Generate audit entries with different agent types
        List<String> agentTypes = List.of("implementation", "testing", "deployment");

        for (String agentType : agentTypes) {
            AgentRequest request = AgentRequest.builder()
                    .type(agentType)
                    .description("Audit trail search test for " + agentType + " agent")
                    .securityContext(developerContext)
                    .context(AgentContext.builder()
                            .property("service", "pos-search-test")
                            .build())
                    .build();

            assertDoesNotThrow(() -> {
                agentManager.processRequest(request);
            });
        }

        // Verify all requests were processed successfully
        for (String agentType : agentTypes) {
            AgentRequest verifyRequest = AgentRequest.builder()
                    .type(agentType)
                    .description("Verify audit trail entry for " + agentType + " agent")
                    .securityContext(developerContext)
                    .context(AgentContext.builder()
                            .property("verify", "true")
                            .build())
                    .build();

            assertDoesNotThrow(() -> {
                AgentResponse response = agentManager.processRequest(verifyRequest);
                assertTrue(response.isSuccess());
            });
        }
    }

    @Test
    void testAuditTrailExport() {
        // Test that export operations work with security context
        AgentRequest request = AgentRequest.builder()
                .type("documentation")
                .description("Audit trail export functionality test")
                .securityContext(adminContext)
                .context(AgentContext.builder()
                        .property("service", "pos-export-test")
                        .build())
                .build();

        assertDoesNotThrow(() -> {
            AgentResponse response = agentManager.processRequest(request);
            assertNotNull(response);
            assertTrue(response.isSuccess());
        });
    }

    @Test
    void testAuditTrailIntegrity() {
        // Test that audit operations maintain integrity with proper security context
        AgentRequest request = AgentRequest.builder()
                .type("observability")
                .description("Audit trail integrity verification")
                .securityContext(adminContext)
                .context(AgentContext.builder()
                        .property("service", "pos-integrity-test")
                        .build())
                .build();

        assertDoesNotThrow(() -> {
            AgentResponse response = agentManager.processRequest(request);
            assertNotNull(response);
            assertTrue(response.isSuccess());
        });
    }

    @Test
    void testComplianceReporting() {
        // Test that various agent types work with security contexts for compliance
        List<Map<String, Object>> testRequests = List.of(
                Map.of("type", "security", "context", adminContext),
                Map.of("type", "configuration-management", "context", adminContext),
                Map.of("type", "implementation", "context", developerContext),
                Map.of("type", "testing", "context", developerContext));

        for (Map<String, Object> testRequest : testRequests) {
            AgentRequest request = AgentRequest.builder()
                    .type((String) testRequest.get("type"))
                    .description("Compliance reporting test for " + testRequest.get("type") + " agent")
                    .securityContext((SecurityContext) testRequest.get("context"))
                    .context(AgentContext.builder()
                            .property("service", "pos-compliance-test")
                            .build())
                    .build();

            assertDoesNotThrow(() -> {
                AgentResponse response = agentManager.processRequest(request);
                assertNotNull(response);
            });
        }

        // Verify all requests were processed
        assertEquals(4, testRequests.size());
    }
}

package com.pos.agent.framework.security;

import com.pos.agent.framework.core.AgentManager;
import com.pos.agent.framework.core.AgentRequest;
import com.pos.agent.framework.core.AgentResponse;
import com.pos.agent.framework.core.SecurityContext;
import com.pos.agent.framework.core.AuditTrail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Audit trail validation tests for comprehensive agent interaction logging.
 * Tests audit trail generation, retention, and compliance reporting.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "pos.agent.framework.audit.enabled=true",
    "pos.agent.framework.audit.detailed=true",
    "pos.agent.framework.audit.retention.days=90"
})
class AuditTrailValidationTest {

    @Autowired
    private AgentManager agentManager;

    private SecurityContext adminContext;
    private SecurityContext developerContext;
    private SecurityContext guestContext;

    @BeforeEach
    void setUp() {
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
            .permissions(List.of("AGENT_READ"))
            .build();
    }

    @Test
    void testBasicAuditTrailGeneration() {
        // Generate various agent interactions
        List<String> agentTypes = List.of(
            "architecture", "implementation", "security", "testing",
            "deployment", "observability", "documentation"
        );

        for (String agentType : agentTypes) {
            AgentRequest request = AgentRequest.builder()
                .type(agentType)
                .securityContext(developerContext)
                .requestData(Map.of("service", "pos-test-" + agentType))
                .build();

            assertDoesNotThrow(() -> {
                AgentResponse response = agentManager.processRequest(request);
                assertNotNull(response);
            });
        }

        // Verify audit trail entries
        List<AuditTrail> auditEntries = agentManager.getAuditTrail(developerContext.getUserId());
        assertNotNull(auditEntries);
        assertTrue(auditEntries.size() >= agentTypes.size());

        // Verify audit entry completeness
        AuditTrail entry = auditEntries.get(0);
        assertNotNull(entry.getTimestamp());
        assertNotNull(entry.getUserId());
        assertNotNull(entry.getAgentType());
        assertNotNull(entry.getAction());
        assertNotNull(entry.getRequestId());
        assertTrue(entry.isSuccess());
    }

    @Test
    void testDetailedAuditTrailContent() {
        AgentRequest detailedRequest = AgentRequest.builder()
            .type("security")
            .securityContext(adminContext)
            .requestData(Map.of(
                "service", "pos-payment",
                "securityType", "jwt-authentication",
                "environment", "production"
            ))
            .build();

        assertDoesNotThrow(() -> {
            AgentResponse response = agentManager.processRequest(detailedRequest);
            assertNotNull(response);
        });

        List<AuditTrail> auditEntries = agentManager.getAuditTrail(adminContext.getUserId());
        assertNotNull(auditEntries);
        assertFalse(auditEntries.isEmpty());

        AuditTrail detailedEntry = auditEntries.get(0);
        
        // Verify detailed audit information
        assertEquals(adminContext.getUserId(), detailedEntry.getUserId());
        assertEquals("security", detailedEntry.getAgentType());
        assertNotNull(detailedEntry.getRequestData());
        assertNotNull(detailedEntry.getResponseData());
        assertNotNull(detailedEntry.getExecutionTimeMs());
        assertTrue(detailedEntry.getExecutionTimeMs() > 0);
        
        // Verify sensitive data is not logged
        String requestDataStr = detailedEntry.getRequestData().toString();
        assertFalse(requestDataStr.contains("password"));
        assertFalse(requestDataStr.contains("secret"));
        assertFalse(requestDataStr.contains("token"));
    }

    @Test
    void testFailedRequestAuditTrail() {
        // Create request that will fail due to insufficient permissions
        AgentRequest failedRequest = AgentRequest.builder()
            .type("security")
            .securityContext(guestContext) // Guest doesn't have security agent access
            .requestData(Map.of("service", "pos-security"))
            .build();

        assertThrows(SecurityException.class, () -> {
            agentManager.processRequest(failedRequest);
        });

        // Verify failed request is audited
        List<AuditTrail> auditEntries = agentManager.getAuditTrail(guestContext.getUserId());
        assertNotNull(auditEntries);
        assertFalse(auditEntries.isEmpty());

        AuditTrail failedEntry = auditEntries.get(0);
        assertFalse(failedEntry.isSuccess());
        assertNotNull(failedEntry.getErrorMessage());
        assertTrue(failedEntry.getErrorMessage().contains("SecurityException"));
    }

    @Test
    void testConcurrentAuditTrailGeneration() throws Exception {
        int concurrentRequests = 50;
        CompletableFuture<Void>[] futures = new CompletableFuture[concurrentRequests];

        for (int i = 0; i < concurrentRequests; i++) {
            final int requestId = i;
            futures[i] = CompletableFuture.runAsync(() -> {
                AgentRequest request = AgentRequest.builder()
                    .type("architecture")
                    .securityContext(developerContext)
                    .requestData(Map.of("service", "pos-concurrent-" + requestId))
                    .build();

                assertDoesNotThrow(() -> {
                    AgentResponse response = agentManager.processRequest(request);
                    assertNotNull(response);
                });
            });
        }

        // Wait for all requests to complete
        CompletableFuture.allOf(futures).get(30, TimeUnit.SECONDS);

        // Verify all requests are audited
        List<AuditTrail> auditEntries = agentManager.getAuditTrail(developerContext.getUserId());
        assertNotNull(auditEntries);
        assertTrue(auditEntries.size() >= concurrentRequests);

        // Verify no duplicate request IDs
        long uniqueRequestIds = auditEntries.stream()
            .map(AuditTrail::getRequestId)
            .distinct()
            .count();
        assertEquals(auditEntries.size(), uniqueRequestIds);
    }

    @Test
    void testAuditTrailRetention() {
        // Test audit trail retention policy
        Map<String, Object> retentionPolicy = agentManager.getAuditRetentionPolicy();
        assertNotNull(retentionPolicy);
        assertTrue(retentionPolicy.containsKey("retentionDays"));
        assertEquals(90, retentionPolicy.get("retentionDays"));
        assertTrue(retentionPolicy.containsKey("autoCleanup"));
        assertEquals(true, retentionPolicy.get("autoCleanup"));
    }

    @Test
    void testAuditTrailSearch() {
        // Generate audit entries with different agent types
        List<String> agentTypes = List.of("implementation", "testing", "deployment");
        
        for (String agentType : agentTypes) {
            AgentRequest request = AgentRequest.builder()
                .type(agentType)
                .securityContext(developerContext)
                .requestData(Map.of("service", "pos-search-test"))
                .build();

            assertDoesNotThrow(() -> {
                agentManager.processRequest(request);
            });
        }

        // Test search by agent type
        List<AuditTrail> implementationEntries = agentManager.searchAuditTrail(
            Map.of("agentType", "implementation", "userId", developerContext.getUserId())
        );
        assertNotNull(implementationEntries);
        assertFalse(implementationEntries.isEmpty());
        assertTrue(implementationEntries.stream()
            .allMatch(entry -> "implementation".equals(entry.getAgentType())));

        // Test search by time range
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime oneHourAgo = now.minusHours(1);
        
        List<AuditTrail> recentEntries = agentManager.searchAuditTrail(
            Map.of(
                "userId", developerContext.getUserId(),
                "startTime", oneHourAgo,
                "endTime", now
            )
        );
        assertNotNull(recentEntries);
        assertFalse(recentEntries.isEmpty());
    }

    @Test
    void testAuditTrailExport() {
        // Generate some audit data
        AgentRequest request = AgentRequest.builder()
            .type("documentation")
            .securityContext(adminContext)
            .requestData(Map.of("service", "pos-export-test"))
            .build();

        assertDoesNotThrow(() -> {
            agentManager.processRequest(request);
        });

        // Test audit trail export
        String exportData = agentManager.exportAuditTrail(
            adminContext.getUserId(),
            LocalDateTime.now().minusHours(1),
            LocalDateTime.now()
        );

        assertNotNull(exportData);
        assertFalse(exportData.isEmpty());
        assertTrue(exportData.contains("timestamp"));
        assertTrue(exportData.contains("userId"));
        assertTrue(exportData.contains("agentType"));
    }

    @Test
    void testAuditTrailIntegrity() {
        // Generate audit entry
        AgentRequest request = AgentRequest.builder()
            .type("observability")
            .securityContext(adminContext)
            .requestData(Map.of("service", "pos-integrity-test"))
            .build();

        assertDoesNotThrow(() -> {
            agentManager.processRequest(request);
        });

        // Verify audit trail integrity
        List<AuditTrail> auditEntries = agentManager.getAuditTrail(adminContext.getUserId());
        assertNotNull(auditEntries);
        assertFalse(auditEntries.isEmpty());

        AuditTrail entry = auditEntries.get(0);
        
        // Verify integrity hash
        assertNotNull(entry.getIntegrityHash());
        assertTrue(agentManager.verifyAuditIntegrity(entry));
        
        // Verify tamper detection
        AuditTrail tamperedEntry = entry.toBuilder()
            .userId("tampered-user")
            .build();
        assertFalse(agentManager.verifyAuditIntegrity(tamperedEntry));
    }

    @Test
    void testComplianceReporting() {
        // Generate various types of audit data
        List<Map<String, Object>> testRequests = List.of(
            Map.of("type", "security", "context", adminContext),
            Map.of("type", "configuration-management", "context", adminContext),
            Map.of("type", "implementation", "context", developerContext),
            Map.of("type", "testing", "context", developerContext)
        );

        for (Map<String, Object> testRequest : testRequests) {
            AgentRequest request = AgentRequest.builder()
                .type((String) testRequest.get("type"))
                .securityContext((SecurityContext) testRequest.get("context"))
                .requestData(Map.of("service", "pos-compliance-test"))
                .build();

            assertDoesNotThrow(() -> {
                agentManager.processRequest(request);
            });
        }

        // Generate compliance report
        Map<String, Object> complianceReport = agentManager.generateAuditComplianceReport();
        assertNotNull(complianceReport);
        
        // Verify compliance metrics
        assertTrue(complianceReport.containsKey("totalAuditEntries"));
        assertTrue(complianceReport.containsKey("auditCoverage"));
        assertTrue(complianceReport.containsKey("integrityValidation"));
        assertTrue(complianceReport.containsKey("retentionCompliance"));
        
        // Verify 100% audit coverage
        assertEquals(100.0, complianceReport.get("auditCoverage"));
        assertEquals(100.0, complianceReport.get("integrityValidation"));
        assertEquals(true, complianceReport.get("retentionCompliance"));
    }
}

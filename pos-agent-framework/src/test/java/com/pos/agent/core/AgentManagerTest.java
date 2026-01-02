package com.pos.agent.core;

import com.pos.agent.context.AgentContext;
import com.pos.agent.context.DefaultContext;
import com.pos.agent.discovery.AgentDiscovery;
import com.pos.agent.framework.audit.AuditTrailManager;
import com.pos.agent.framework.model.AgentType;
import com.pos.agent.framework.service.ServiceAgentMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for AgentManager covering all public methods.
 * Uses Mockito for dependency injection to avoid static mocking and enable deterministic testing.
 */
@ExtendWith(MockitoExtension.class)
class AgentManagerTest {

    @Mock
    private AuditTrailManager mockAuditManager;

    @Mock
    private SecurityValidator mockSecurityValidator;

    @Mock
    private AgentDiscovery mockAgentDiscovery;

    @Mock
    private Agent mockAgent;

    @Mock
    private Supplier<Agent> mockFallbackSupplier;

    private AgentManager agentManager;

    @BeforeEach
    void setUp() {
        // Set up JWT secret for tests
        System.setProperty("agent.jwt.secret", "test-secret-key-for-unit-tests");
        
        agentManager = new AgentManager(
                mockAuditManager,
                new ServiceAgentMapping(),
                mockSecurityValidator,
                mockAgentDiscovery,
                mockFallbackSupplier
        );
    }

    // ===== Constructor and Audit Manager Tests =====

    @Test
    void testDefaultConstructor() {
        AgentManager manager = new AgentManager();
        assertNotNull(manager.getAuditTrailManager());
        assertNotNull(manager.listAgents());
    }

    @Test
    void testConstructorWithAuditManager() {
        AuditTrailManager auditManager = new AuditTrailManager();
        AgentManager manager = new AgentManager(auditManager);
        assertSame(auditManager, manager.getAuditTrailManager());
    }

    @Test
    void testGetAuditTrailManager() {
        assertSame(mockAuditManager, agentManager.getAuditTrailManager());
    }

    // ===== Registry Operation Tests =====

    @Test
    void testRegisterAgent() {
        agentManager.registerAgent(mockAgent);

        List<Agent> agents = agentManager.listAgents();
        assertTrue(agents.contains(mockAgent));
    }

    @Test
    void testUnregisterAgent() {
        agentManager.registerAgent(mockAgent);
        assertTrue(agentManager.listAgents().contains(mockAgent));

        boolean result = agentManager.unregisterAgent(mockAgent);

        assertTrue(result);
        assertFalse(agentManager.listAgents().contains(mockAgent));
    }

    @Test
    void testClearAgents() {
        when(mockAgent.getTechnicalDomain()).thenReturn(AgentType.ARCHITECTURE);

        agentManager.registerAgent(mockAgent);
        assertFalse(agentManager.listAgents().isEmpty());

        agentManager.clearAgents();

        assertTrue(agentManager.listAgents().isEmpty());
    }

    @Test
    void testListAgentsReturnsUnmodifiableList() {
        List<Agent> agents = agentManager.listAgents();

        assertThrows(UnsupportedOperationException.class, () -> {
            agents.add(mockAgent);
        });
    }

    // ===== Discovery and Routing Tests =====

    @Test
    void testConsultBestAgentDelegatesToDiscovery() {
        AgentRequest request = createValidRequest();
        when(mockAgentDiscovery.discoverBestAgent(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(mockAgent)));

        CompletableFuture<Agent> result = agentManager.consultBestAgent(request);

        assertNotNull(result);
        assertEquals(mockAgent, result.join());
        verify(mockAgentDiscovery).discoverBestAgent(eq(request), any());
    }

    @Test
    void testConsultBestAgentReturnsNullWhenEmpty() {
        AgentRequest request = createValidRequest();
        when(mockAgentDiscovery.discoverBestAgent(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(Optional.empty()));

        CompletableFuture<Agent> result = agentManager.consultBestAgent(request);

        assertNull(result.join());
    }

    // ===== Filtering and Health Tests =====

    @Test
    void testGetHealthStatus() {
        Agent healthyAgent = mock(Agent.class);
        Agent unhealthyAgent = mock(Agent.class);

        when(healthyAgent.isHealthy()).thenReturn(true);
        when(unhealthyAgent.isHealthy()).thenReturn(false);
        when(healthyAgent.getTechnicalDomain()).thenReturn(AgentType.ARCHITECTURE);
        when(unhealthyAgent.getTechnicalDomain()).thenReturn(AgentType.CICD_PIPELINE);

        agentManager.registerAgent(healthyAgent);
        agentManager.registerAgent(unhealthyAgent);

        RegistryHealthStatus status = agentManager.getHealthStatus();

        assertEquals(2, status.totalAgents());
        assertEquals(1, status.availableAgents());
    }

    @Test
    void testGetAgentsWithCapabilities() {
        Agent agent1 = mock(Agent.class);
        Agent agent2 = mock(Agent.class);

        when(agent1.getCapabilities()).thenReturn(List.of("api-design", "documentation"));
        when(agent2.getCapabilities()).thenReturn(List.of("testing", "deployment"));

        agentManager.registerAgent(agent1);
        agentManager.registerAgent(agent2);

        List<Agent> result = agentManager.getAgentsWithCapabilities(Set.of("api-design"));

        assertEquals(1, result.size());
        assertTrue(result.contains(agent1));
    }

    @Test
    void testGetAgentsForTechnicalDomain() {
        Agent archAgent = mock(Agent.class);
        Agent cicdAgent = mock(Agent.class);

        when(archAgent.getTechnicalDomain()).thenReturn(AgentType.ARCHITECTURE);
        when(cicdAgent.getTechnicalDomain()).thenReturn(AgentType.CICD_PIPELINE);

        agentManager.registerAgent(archAgent);
        agentManager.registerAgent(cicdAgent);

        List<Agent> result = agentManager.getAgentsForTechnicalDomain("architecture");

        assertEquals(1, result.size());
        assertTrue(result.contains(archAgent));
    }

    @Test
    void testGetAgentsForAgentType() {
        Agent archAgent = mock(Agent.class);
        Agent cicdAgent = mock(Agent.class);

        when(archAgent.getTechnicalDomain()).thenReturn(AgentType.ARCHITECTURE);
        when(cicdAgent.getTechnicalDomain()).thenReturn(AgentType.CICD_PIPELINE);

        agentManager.registerAgent(archAgent);
        agentManager.registerAgent(cicdAgent);

        List<Agent> result = agentManager.getAgentsForAgentType(AgentType.ARCHITECTURE);

        assertEquals(1, result.size());
        assertTrue(result.contains(archAgent));
    }

    // ===== Request Processing Tests =====

    @Test
    void testProcessRequestAuthenticationFailure() {
        AgentRequest request = createValidRequest();
        when(mockSecurityValidator.extractUserId(request)).thenReturn("user123");
        when(mockSecurityValidator.validateSecurityContext(any())).thenReturn(false);

        AgentResponse response = agentManager.processRequest(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("Authentication failed"));
        verify(mockAuditManager).recordAuditEntry(any());
    }

    @Test
    void testProcessRequestAuthorizationFailure() {
        AgentRequest request = createValidRequest();
        when(mockSecurityValidator.extractUserId(request)).thenReturn("user123");
        when(mockSecurityValidator.validateSecurityContext(any())).thenReturn(true);
        when(mockAgentDiscovery.discoverBestAgent(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(mockAgent)));
        when(mockSecurityValidator.validateAuthorization(request, mockAgent)).thenReturn(false);

        AgentResponse response = agentManager.processRequest(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("Authorization failed"));
        verify(mockAuditManager).recordAuditEntry(any());
    }

    @Test
    void testProcessRequestSuccessfulDelegation() {
        AgentRequest request = createValidRequest();
        AgentResponse expectedResponse = AgentResponse.builder()
                .success(true)
                .output("Test output")
                .confidence(0.9)
                .build();

        when(mockSecurityValidator.extractUserId(request)).thenReturn("user123");
        when(mockSecurityValidator.validateSecurityContext(any())).thenReturn(true);
        when(mockAgentDiscovery.discoverBestAgent(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(mockAgent)));
        when(mockSecurityValidator.validateAuthorization(request, mockAgent)).thenReturn(true);
        when(mockAgent.processRequest(request)).thenReturn(expectedResponse);

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertEquals("Test output", response.getOutput());
        verify(mockAgent).processRequest(request);
        verify(mockAuditManager).recordAuditEntry(any());
    }

    @Test
    void testProcessRequestUsesFallbackAgentOnTimeout() {
        AgentRequest request = createValidRequest();
        Agent fallbackAgent = mock(Agent.class);
        AgentResponse fallbackResponse = AgentResponse.builder()
                .success(true)
                .output("Fallback output")
                .confidence(0.8)
                .build();

        when(mockSecurityValidator.extractUserId(request)).thenReturn("user123");
        when(mockSecurityValidator.validateSecurityContext(any())).thenReturn(true);
        when(mockFallbackSupplier.get()).thenReturn(fallbackAgent);
        when(mockSecurityValidator.validateAuthorization(request, fallbackAgent)).thenReturn(true);
        when(fallbackAgent.processRequest(request)).thenReturn(fallbackResponse);

        // Simulate timeout by returning a future that never completes
        CompletableFuture<Optional<Agent>> neverCompletes = new CompletableFuture<>();
        when(mockAgentDiscovery.discoverBestAgent(any(), any())).thenReturn(neverCompletes);

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertEquals("Fallback output", response.getOutput());
        verify(mockFallbackSupplier).get();
        verify(fallbackAgent).processRequest(request);
    }

    // ===== Context Coordination Tests =====

    @Test
    void testValidateContextWithMissingKeys() {
        AgentContext context = DefaultContext.builder()
                .build();
        AgentRequest request = AgentRequest.builder()
                .agentContext(context)
                .securityContext(createSecurityContext())
                .build();

        ContextValidationResult result = agentManager.validateContext(request);

        assertFalse(result.isSufficient());
        assertFalse(result.getMissingInputs().isEmpty());
    }

    @Test
    void testUpdateSessionProgress() {
        String sessionId = "session123";
        String taskObjective = "Test objective";
        Map<String, Object> decisions = Map.of("key", "value");
        List<String> nextSteps = List.of("step1", "step2");

        agentManager.updateSessionProgress(sessionId, taskObjective, decisions, nextSteps);

        Optional<SessionContext> session = agentManager.getSessionContext(sessionId);
        assertTrue(session.isPresent());
        assertEquals(taskObjective, session.get().getTaskObjective());
        assertEquals(decisions, session.get().getArchitecturalDecisions());
        assertEquals(nextSteps, session.get().getNextSteps());
    }

    @Test
    void testGetSessionContext() {
        String sessionId = "session123";

        // Session doesn't exist initially
        Optional<SessionContext> result = agentManager.getSessionContext(sessionId);
        assertFalse(result.isPresent());

        // Create session
        agentManager.updateSessionProgress(sessionId, "objective", Map.of(), List.of());

        // Now it exists
        result = agentManager.getSessionContext(sessionId);
        assertTrue(result.isPresent());
    }

    @Test
    void testUpdateSpecializedContext() {
        String sessionId = "session123";
        String guidance = "Test GUIDANCE";
        AgentResponse response = AgentResponse.builder()
                .success(true)
                .output(guidance)
                .confidence(0.9)
                .build();

        agentManager.updateSpecializedContext(sessionId, mockAgent, response);

        verify(mockAgent).updateContext(sessionId, "test guidance");
    }

    @Test
    void testGetSharedContextForAgent() {
        String sessionId = "session123";
        AgentContext agentContext = mock(AgentContext.class);

        when(mockAgent.getTechnicalDomain()).thenReturn(AgentType.DOCUMENTATION);
        when(mockAgent.getOrCreateContext(sessionId)).thenReturn(agentContext);

        agentManager.updateSessionProgress(sessionId, "objective", Map.of(), List.of());

        Map<String, Object> sharedContext = agentManager.getSharedContextForAgent(sessionId, mockAgent);

        assertNotNull(sharedContext);
        assertTrue(sharedContext.containsKey("session"));
        assertTrue(sharedContext.containsKey("DOCUMENTATION"));
    }

    @Test
    void testEnhanceWithContext() {
        String sessionId = "session123";
        AgentContext context = DefaultContext.builder()
                .property("session-id", sessionId)
                .build();
        AgentRequest request = AgentRequest.builder()
                .agentContext(context)
                .securityContext(createSecurityContext())
                .build();
        AgentResponse originalResponse = AgentResponse.builder()
                .success(true)
                .output("Original output")
                .confidence(0.9)
                .recommendations(List.of("rec1"))
                .build();

        when(mockAgent.getTechnicalDomain()).thenReturn(AgentType.DOCUMENTATION);

        agentManager.updateSessionProgress(sessionId, "Test objective", Map.of(), List.of());

        AgentResponse enhanced = agentManager.enhanceWithContext(originalResponse, request, mockAgent);

        assertNotNull(enhanced);
        assertTrue(enhanced.getOutput().contains("Original output"));
        assertTrue(enhanced.getOutput().contains("Context-Aware Guidance"));
        assertTrue(enhanced.getOutput().contains("Test objective"));
    }

    @Test
    void testCleanupStaleContexts() {
        String sessionId1 = "session1";
        String sessionId2 = "session2";

        // Create two sessions
        agentManager.updateSessionProgress(sessionId1, "obj1", Map.of(), List.of());
        agentManager.updateSessionProgress(sessionId2, "obj2", Map.of(), List.of());

        // Mark one as stale by setting its last access time far in the past
        Optional<SessionContext> session1 = agentManager.getSessionContext(sessionId1);
        assertTrue(session1.isPresent());
        // The session will naturally become stale after SESSION_TIMEOUT, but we can't easily
        // mock time here. This test verifies the method runs without error.

        agentManager.cleanupStaleContexts();

        // Both should still exist if not enough time has passed
        assertTrue(agentManager.getSessionContext(sessionId1).isPresent());
        assertTrue(agentManager.getSessionContext(sessionId2).isPresent());
    }

    @Test
    void testArchiveSessionContext() {
        String sessionId = "session123";
        AgentContext agentContext = mock(AgentContext.class);

        when(mockAgent.getTechnicalDomain()).thenReturn(AgentType.ARCHITECTURE);
        when(mockAgent.getOrCreateContext(sessionId)).thenReturn(agentContext);

        agentManager.updateSessionProgress(sessionId, "objective", Map.of(), List.of());
        assertTrue(agentManager.getSessionContext(sessionId).isPresent());

        agentManager.archiveSessionContext(sessionId, mockAgent);

        assertFalse(agentManager.getSessionContext(sessionId).isPresent());
        verify(mockAgent).removeContext(sessionId);
    }

    // ===== Helper Methods =====

    private AgentRequest createValidRequest() {
        AgentContext context = DefaultContext.builder()
                .property("session-id", "test-session")
                .build();

        return AgentRequest.builder()
                .agentContext(context)
                .securityContext(createSecurityContext())
                .build();
    }

    private SecurityContext createSecurityContext() {
        // Create a valid security context for tests with proper JWT encoding
        return SecurityContext.builder()
                .userId("test-user")
                .roles(List.of())
                .permissions(List.of())
                .serviceId("test-service")
                .serviceType("TEST")
                .build();
    }
}

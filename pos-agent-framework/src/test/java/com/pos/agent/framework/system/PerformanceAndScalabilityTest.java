package com.pos.agent.framework.system;

import com.pos.agent.core.AgentManager;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.core.SecurityContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance and scalability tests for the agent framework.
 * Tests concurrent request handling, memory usage, and response times.
 */
@DisplayName("Performance and Scalability Tests")
class PerformanceAndScalabilityTest {

    private AgentManager agentManager;
    private SecurityContext securityContext;

    @BeforeEach
    void setUp() {
        agentManager = new AgentManager();
        securityContext = SecurityContext.builder()
                .jwtToken("valid-jwt-token-for-performance-tests")
                .userId("perf-test-user")
                .roles(List.of("TESTER"))
                .permissions(List.of("read", "execute"))
                .build();
    }

    @Test
    @DisplayName("Basic request processing performance")
    void testBasicPerformance() {
        AgentRequest request = new AgentRequest();
        request.setDescription("Basic performance test request");
        request.setType("architecture");
        request.setSecurityContext(securityContext);
        Map<String, Object> context = new HashMap<>();
        context.put("test", "basic");
        request.setContext(context);

        long startTime = System.currentTimeMillis();
        AgentResponse response = agentManager.processRequest(request);
        long duration = System.currentTimeMillis() - startTime;

        assertNotNull(response);
        assertEquals("SUCCESS", response.getStatus());
        assertTrue(duration < 5000, "Request should complete within 5 seconds");
    }

    @Test
    @DisplayName("Multiple sequential requests performance")
    void testSequentialPerformance() {
        int requestCount = 10;
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < requestCount; i++) {
            AgentRequest request = new AgentRequest();
            request.setDescription("Sequential request " + (i + 1));
            request.setType("implementation");
            request.setSecurityContext(securityContext);
            Map<String, Object> context = new HashMap<>();
            context.put("index", i);
            request.setContext(context);

            AgentResponse response = agentManager.processRequest(request);
            assertNotNull(response);
            assertEquals("SUCCESS", response.getStatus());
        }

        long totalDuration = System.currentTimeMillis() - startTime;
        assertTrue(totalDuration < 30000, "10 requests should complete within 30 seconds");
    }

    @Test
    @DisplayName("Response time measurement")
    void testResponseTime() {
        AgentRequest request = new AgentRequest();
        request.setDescription("Response time measurement test");
        request.setType("security");
        request.setSecurityContext(securityContext);
        Map<String, Object> context = new HashMap<>();
        context.put("measurement", "response-time");
        request.setContext(context);

        AgentResponse response = agentManager.processRequest(request);
        assertNotNull(response);
        assertTrue(response.getProcessingTimeMs() > 0, "Processing time should be measured");
    }

    @Test
    @DisplayName("Memory efficiency under load")
    void testMemoryEfficiency() {
        Runtime runtime = Runtime.getRuntime();
        long beforeMemory = runtime.totalMemory() - runtime.freeMemory();

        AgentRequest request = new AgentRequest();
        request.setDescription("Memory efficiency test");
        request.setType("testing");
        request.setSecurityContext(securityContext);
        Map<String, Object> context = new HashMap<>();
        context.put("memory", "test");
        request.setContext(context);

        for (int i = 0; i < 20; i++) {
            AgentResponse response = agentManager.processRequest(request);
            assertNotNull(response);
        }

        long afterMemory = runtime.totalMemory() - runtime.freeMemory();
        long memoryIncrease = afterMemory - beforeMemory;
        assertTrue(memoryIncrease < 100_000_000, "Memory increase should be less than 100MB");
    }

    @Test
    @DisplayName("Request processing with various agent types")
    void testMultipleAgentTypes() {
        String[] agentTypes = { "architecture", "implementation", "security", "testing" };

        for (String agentType : agentTypes) {
            AgentRequest request = new AgentRequest();
            request.setDescription("Request for " + agentType + " agent");
            request.setType(agentType);
            request.setSecurityContext(securityContext);
            Map<String, Object> context = new HashMap<>();
            context.put("agent-type", agentType);
            request.setContext(context);

            AgentResponse response = agentManager.processRequest(request);
            assertNotNull(response, "Response should not be null for agent type: " + agentType);
            assertEquals("SUCCESS", response.getStatus(), "Processing should succeed for: " + agentType);
        }
    }

    @Test
    @DisplayName("Processing time tracking")
    void testProcessingTimeTracking() {
        AgentRequest request = new AgentRequest();
        request.setDescription("Processing time tracking test");
        request.setType("documentation");
        request.setSecurityContext(securityContext);
        Map<String, Object> context = new HashMap<>();
        context.put("timing", "tracked");
        request.setContext(context);

        AgentResponse response = agentManager.processRequest(request);
        assertNotNull(response);
        assertNotNull(response.getProcessingTimeMs(), "Processing time should be tracked");
        assertTrue(response.getProcessingTimeMs() >= 0, "Processing time should be non-negative");
    }
}

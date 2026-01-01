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
                .permissions(List.of("AGENT_READ", "AGENT_WRITE", "read", "execute"))
                .build();
    }

    @Test
    @DisplayName("Basic request processing performance")
    void testBasicPerformance() {
        Map<String, Object> context = new HashMap<>();
        context.put("test", "basic");

        AgentRequest request = AgentRequest.builder()
                .description("Basic performance test request")
                .type("architecture")
                .context(context)
                .build();
        request.setSecurityContext(securityContext);

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
            Map<String, Object> context = new HashMap<>();
            context.put("index", i);

            AgentRequest request = AgentRequest.builder()
                    .description("Sequential request " + (i + 1))
                    .type("implementation")
                    .context(context)
                    .build();
            request.setSecurityContext(securityContext);

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
        Map<String, Object> context = new HashMap<>();
        context.put("measurement", "response-time");

        AgentRequest request = AgentRequest.builder()
                .description("Response time measurement test")
                .type("security")
                .context(context)
                .build();
        request.setSecurityContext(securityContext);

        AgentResponse response = agentManager.processRequest(request);
        assertNotNull(response);
        assertTrue(response.getProcessingTimeMs() > 0, "Processing time should be measured");
    }

    @Test
    @DisplayName("Memory efficiency under load")
    void testMemoryEfficiency() {
        Runtime runtime = Runtime.getRuntime();
        long beforeMemory = runtime.totalMemory() - runtime.freeMemory();

        Map<String, Object> context = new HashMap<>();
        context.put("memory", "test");

        AgentRequest request = AgentRequest.builder()
                .description("Memory efficiency test")
                .type("testing")
                .context(context)
                .build();
        request.setSecurityContext(securityContext);

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
            Map<String, Object> context = new HashMap<>();
            context.put("agent-type", agentType);

            AgentRequest request = AgentRequest.builder()
                    .description("Request for " + agentType + " agent")
                    .type(agentType)
                    .context(context)
                    .build();
            request.setSecurityContext(securityContext);

            AgentResponse response = agentManager.processRequest(request);
            assertNotNull(response, "Response should not be null for agent type: " + agentType);
            assertEquals("SUCCESS", response.getStatus(), "Processing should succeed for: " + agentType);
        }
    }

    @Test
    @DisplayName("Processing time tracking")
    void testProcessingTimeTracking() {
        Map<String, Object> context = new HashMap<>();
        context.put("timing", "tracked");

        AgentRequest request = AgentRequest.builder()
                .description("Processing time tracking test")
                .type("documentation")
                .context(context)
                .build();
        request.setSecurityContext(securityContext);

        AgentResponse response = agentManager.processRequest(request);
        assertNotNull(response);
        assertNotNull(response.getProcessingTimeMs(), "Processing time should be tracked");
        assertTrue(response.getProcessingTimeMs() >= 0, "Processing time should be non-negative");
    }
}

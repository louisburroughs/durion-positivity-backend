package com.pos.agent.performance;

import com.positivity.agent.Agent;
import com.positivity.agent.AgentConsultationRequest;
import com.positivity.agent.AgentGuidanceResponse;
import com.positivity.agent.registry.AgentRegistry;
import com.positivity.agent.controller.AgentConsultationController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class AgentPerformanceTest {

    @Autowired
    private AgentConsultationController agentController;

    @Autowired
    private AgentRegistry agentRegistry;

    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        executorService = Executors.newFixedThreadPool(50);
    }

    @Test
    void testConcurrentRequestPerformance() throws Exception {
        // Test concurrent requests to all 15 agent types
        int concurrentRequests = 100;
        List<CompletableFuture<AgentResponse>> futures = new ArrayList<>();

        Instant startTime = Instant.now();

        for (int i = 0; i < concurrentRequests; i++) {
            AgentType agentType = getRandomAgentType(i);
            AgentRequest request = createTestRequest(agentType);

            CompletableFuture<AgentResponse> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return agentManager.processRequest(request);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, executorService);

            futures.add(future);
        }

        // Wait for all requests to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);

        Instant endTime = Instant.now();
        Duration totalDuration = Duration.between(startTime, endTime);

        // Validate performance targets
        assertTrue(totalDuration.toMillis() < 3000,
                "System response time should be ≤ 3 seconds for 99% of requests. Actual: " + totalDuration.toMillis()
                        + "ms");

        // Check individual response times
        long responsesUnder500ms = futures.stream()
                .mapToLong(future -> {
                    try {
                        AgentResponse response = future.get();
                        return response.getProcessingTimeMs();
                    } catch (Exception e) {
                        return Long.MAX_VALUE;
                    }
                })
                .filter(time -> time <= 500)
                .count();

        double percentageUnder500ms = (double) responsesUnder500ms / concurrentRequests * 100;
        assertTrue(percentageUnder500ms >= 95.0,
                "95% of agent responses should be ≤ 500ms. Actual: " + percentageUnder500ms + "%");
    }

    @Test
    void testAgentResponseTimePerformance() {
        // Test individual agent response times
        for (AgentType agentType : AgentType.values()) {
            Agent agent = agentRegistry.findAgent(agentType);
            assertNotNull(agent, "Agent should be available: " + agentType);

            AgentRequest request = createTestRequest(agentType);
            Instant startTime = Instant.now();

            AgentResponse response = agentManager.processRequest(request);

            Instant endTime = Instant.now();
            long responseTime = Duration.between(startTime, endTime).toMillis();

            assertNotNull(response);
            assertTrue(responseTime <= 500,
                    "Agent " + agentType + " response time should be ≤ 500ms. Actual: " + responseTime + "ms");
        }
    }

    @Test
    void testMemoryUsageUnderLoad() throws Exception {
        Runtime runtime = Runtime.getRuntime();
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();

        // Generate load with multiple concurrent requests
        int loadRequests = 200;
        List<CompletableFuture<AgentResponse>> futures = new ArrayList<>();

        for (int i = 0; i < loadRequests; i++) {
            AgentType agentType = getRandomAgentType(i);
            AgentRequest request = createTestRequest(agentType);

            CompletableFuture<AgentResponse> future = CompletableFuture.supplyAsync(() -> {
                return agentManager.processRequest(request);
            }, executorService);

            futures.add(future);
        }

        // Wait for completion
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(60, TimeUnit.SECONDS);

        // Force garbage collection and measure memory
        System.gc();
        Thread.sleep(1000);
        long finalMemory = runtime.totalMemory() - runtime.freeMemory();
        long memoryIncrease = finalMemory - initialMemory;

        // Memory increase should be reasonable (less than 512MB for this test)
        long maxMemoryIncrease = 512 * 1024 * 1024; // 512MB
        assertTrue(memoryIncrease < maxMemoryIncrease,
                "Memory usage increase should be < 512MB. Actual increase: " + (memoryIncrease / 1024 / 1024) + "MB");
    }

    @Test
    void testAgentRegistryPerformance() {
        // Test agent discovery performance
        Instant startTime = Instant.now();

        for (int i = 0; i < 1000; i++) {
            AgentType agentType = getRandomAgentType(i);
            Agent agent = agentRegistry.findAgent(agentType);
            assertNotNull(agent);
        }

        Instant endTime = Instant.now();
        long discoveryTime = Duration.between(startTime, endTime).toMillis();

        // Agent discovery should be very fast (< 100ms for 1000 lookups)
        assertTrue(discoveryTime < 100,
                "Agent discovery should be fast. 1000 lookups took: " + discoveryTime + "ms");
    }

    @Test
    void testLoadBalancingPerformance() throws Exception {
        // Test load balancing across multiple agent instances
        AgentType testAgentType = AgentType.IMPLEMENTATION;
        int requests = 50;
        List<CompletableFuture<AgentResponse>> futures = new ArrayList<>();

        Instant startTime = Instant.now();

        for (int i = 0; i < requests; i++) {
            AgentRequest request = createTestRequest(testAgentType);

            CompletableFuture<AgentResponse> future = CompletableFuture.supplyAsync(() -> {
                return agentManager.processRequest(request);
            }, executorService);

            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(15, TimeUnit.SECONDS);

        Instant endTime = Instant.now();
        Duration totalTime = Duration.between(startTime, endTime);

        // Load balancing should distribute requests efficiently
        assertTrue(totalTime.toMillis() < 2000,
                "Load balanced requests should complete quickly. Actual: " + totalTime.toMillis() + "ms");

        // Verify all requests succeeded
        long successfulRequests = futures.stream()
                .mapToLong(future -> {
                    try {
                        AgentResponse response = future.get();
                        return response.isSuccess() ? 1 : 0;
                    } catch (Exception e) {
                        return 0;
                    }
                })
                .sum();

        assertEquals(requests, successfulRequests, "All load balanced requests should succeed");
    }

    private AgentType getRandomAgentType(int index) {
        AgentType[] types = AgentType.values();
        return types[index % types.length];
    }

    private AgentRequest createTestRequest(AgentType agentType) {
        AgentRequest request = new AgentRequest();
        request.setAgentType(agentType);
        request.setRequestId("perf-test-" + System.nanoTime());

        switch (agentType) {
            case ARCHITECTURE:
                request.setQuery("Design microservice architecture for inventory service");
                break;
            case IMPLEMENTATION:
                request.setQuery("Implement REST controller for product management");
                break;
            case DEPLOYMENT:
                request.setQuery("Configure Kubernetes deployment for order service");
                break;
            case TESTING:
                request.setQuery("Create unit tests for customer service");
                break;
            case SECURITY:
                request.setQuery("Implement JWT authentication for API gateway");
                break;
            case OBSERVABILITY:
                request.setQuery("Configure monitoring for payment service");
                break;
            case DOCUMENTATION:
                request.setQuery("Generate API documentation for catalog service");
                break;
            case BUSINESS_DOMAIN:
                request.setQuery("Validate business rules for pricing service");
                break;
            case INTEGRATION_GATEWAY:
                request.setQuery("Configure API gateway routing for inventory");
                break;
            case PAIR_PROGRAMMING_NAVIGATOR:
                request.setQuery("Review code quality for order processing");
                break;
            case EVENT_DRIVEN_ARCHITECTURE:
                request.setQuery("Design event schema for order events");
                break;
            case CICD_PIPELINE:
                request.setQuery("Configure CI/CD pipeline for customer service");
                break;
            case CONFIGURATION_MANAGEMENT:
                request.setQuery("Manage configuration for payment service");
                break;
            case RESILIENCE_ENGINEERING:
                request.setQuery("Implement circuit breaker for external API");
                break;
            default:
                request.setQuery("Generic performance test query");
        }

        return request;
    }
}

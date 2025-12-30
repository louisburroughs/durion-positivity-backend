package com.pos.agent.performance;

import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.core.AgentManager;
import com.pos.agent.framework.model.AgentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class AgentPerformanceTest {

    private AgentManager agentManager;

    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        agentManager = new AgentManager();
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
    void testAgentRequestProcessingPerformance() {
        // Test agent request processing performance
        Instant startTime = Instant.now();

        for (int i = 0; i < 1000; i++) {
            AgentType agentType = getRandomAgentType(i);
            AgentRequest request = createTestRequest(agentType);
            AgentResponse response = agentManager.processRequest(request);
            assertNotNull(response);
        }

        Instant endTime = Instant.now();
        long processingTime = Duration.between(startTime, endTime).toMillis();

        // Request processing should be very fast (< 2000ms for 1000 requests)
        assertTrue(processingTime < 2000,
                "Request processing should be fast. 1000 requests took: " + processingTime + "ms");
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
        request.setType(agentType.name());
        request.setDescription("perf-test-" + System.nanoTime());

        switch (agentType) {
            case ARCHITECTURE:
                request.setDescription("Design microservice architecture for inventory service");
                break;
            case IMPLEMENTATION:
                request.setDescription("Implement REST controller for product management");
                break;
            case TESTING:
                request.setDescription("Create unit tests for customer service");
                break;
            case SECURITY:
                request.setDescription("Implement JWT authentication for API gateway");
                break;
            case DOCUMENTATION:
                request.setDescription("Generate API documentation for catalog service");
                break;
            case BUSINESS_DOMAIN:
                request.setDescription("Validate business rules for pricing service");
                break;
            case INTEGRATION_GATEWAY:
                request.setDescription("Configure API gateway routing for inventory");
                break;
            case EVENT_DRIVEN_ARCHITECTURE:
                request.setDescription("Design event schema for order events");
                break;
            case CICD_PIPELINE:
                request.setDescription("Configure CI/CD pipeline for customer service");
                break;
            case CONFIGURATION_MANAGEMENT:
                request.setDescription("Manage configuration for payment service");
                break;
            case RESILIENCE_ENGINEERING:
                request.setDescription("Implement circuit breaker for external API");
                break;
            case PERFORMANCE:
                request.setDescription("Optimize performance for payment service");
                break;
            default:
                request.setDescription("Generic performance test query");
        }

        return request;
    }
}

package com.pos.agent.performance;

import com.pos.agent.core.AgentManager;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.context.AgentContext;
import com.pos.agent.core.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class MemoryUsageTest {

    private AgentManager agentManager;
    private SecurityContext securityContext;
    private MemoryMXBean memoryBean;
    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        agentManager = new AgentManager();
        securityContext = SecurityContext.builder()
                .jwtToken("test-token-12345")
                .userId("test-user")
                .roles(List.of("developer", "architect"))
                .permissions(List.of("AGENT_READ", "AGENT_WRITE", "memory:test", "domain:access"))
                .serviceId("test-service")
                .serviceType("test")
                .build();

        memoryBean = ManagementFactory.getMemoryMXBean();
        executorService = Executors.newFixedThreadPool(20);

        // Force garbage collection before test
        System.gc();
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void testMemoryUsagePerAgent() {
        // Test memory usage for each agent type
        String[] agentTypes = {
                "architecture", "implementation", "deployment", "testing", "security",
                "observability", "documentation", "business-domain", "integration-gateway",
                "pair-programming-navigator", "event-driven-architecture", "cicd-pipeline",
                "configuration-management", "resilience-engineering"
        };

        for (String agentType : agentTypes) {
            MemoryUsage beforeHeap = memoryBean.getHeapMemoryUsage();
            long beforeUsed = beforeHeap.getUsed();

            // Process multiple requests for this agent type
            for (int i = 0; i < 10; i++) {
                AgentRequest request = createTestRequest(agentType, i);
                AgentResponse response = agentManager.processRequest(request);
                assertNotNull(response);
            }

            // Force garbage collection
            System.gc();
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            MemoryUsage afterHeap = memoryBean.getHeapMemoryUsage();
            long afterUsed = afterHeap.getUsed();
            long memoryIncrease = afterUsed - beforeUsed;

            // Memory increase per agent should be reasonable (< 50MB)
            long maxMemoryPerAgent = 50 * 1024 * 1024; // 50MB
            assertTrue(memoryIncrease < maxMemoryPerAgent,
                    "Agent " + agentType + " memory usage should be < 50MB. Actual: " +
                            (memoryIncrease / 1024 / 1024) + "MB");
        }
    }

    @Test
    void testMemoryLeakDetection() throws Exception {
        String[] domains = {
                "architecture", "implementation", "deployment", "testing", "security",
                "observability", "documentation", "business-domain", "integration-gateway",
                "pair-programming-navigator", "event-driven-architecture", "cicd-pipeline",
                "configuration-management", "resilience-engineering"
        };

        MemoryUsage initialHeap = memoryBean.getHeapMemoryUsage();
        long initialUsed = initialHeap.getUsed();

        // Run multiple cycles of agent requests
        for (int cycle = 0; cycle < 5; cycle++) {
            List<CompletableFuture<AgentResponse>> futures = new ArrayList<>();

            // Create concurrent requests
            for (int i = 0; i < 20; i++) {
                String domain = domains[i % domains.length];
                AgentRequest request = createTestRequest(domain, i);

                CompletableFuture<AgentResponse> future = CompletableFuture.supplyAsync(() -> {
                    return agentManager.processRequest(request);
                }, executorService);

                futures.add(future);
            }

            // Wait for completion
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);

            // Force garbage collection between cycles
            System.gc();
            Thread.sleep(1000);
        }

        MemoryUsage finalHeap = memoryBean.getHeapMemoryUsage();
        long finalUsed = finalHeap.getUsed();
        long totalIncrease = finalUsed - initialUsed;

        // Memory should not continuously grow (potential leak detection)
        long maxAcceptableIncrease = 100 * 1024 * 1024; // 100MB
        assertTrue(totalIncrease < maxAcceptableIncrease,
                "Memory usage should not continuously grow. Total increase: " +
                        (totalIncrease / 1024 / 1024) + "MB");
    }

    @Test
    void testMemoryUsageUnderSustainedLoad() throws Exception {
        String[] domains = {
                "architecture", "implementation", "deployment", "testing", "security",
                "observability", "documentation", "business-domain", "integration-gateway",
                "pair-programming-navigator", "event-driven-architecture", "cicd-pipeline",
                "configuration-management", "resilience-engineering"
        };

        MemoryUsage beforeHeap = memoryBean.getHeapMemoryUsage();
        long beforeUsed = beforeHeap.getUsed();

        // Sustained load for 30 seconds
        long endTime = System.currentTimeMillis() + 30000; // 30 seconds
        int requestCount = 0;

        while (System.currentTimeMillis() < endTime) {
            String domain = domains[requestCount % domains.length];
            AgentRequest request = createTestRequest(domain, requestCount);

            AgentResponse response = agentManager.processRequest(request);
            assertNotNull(response);

            requestCount++;

            // Periodic garbage collection
            if (requestCount % 50 == 0) {
                System.gc();
            }
        }

        // Final garbage collection
        System.gc();
        Thread.sleep(2000);

        MemoryUsage afterHeap = memoryBean.getHeapMemoryUsage();
        long afterUsed = afterHeap.getUsed();
        long memoryIncrease = afterUsed - beforeUsed;

        // Memory usage should remain stable under sustained load
        long maxSustainedIncrease = 200 * 1024 * 1024; // 200MB
        assertTrue(memoryIncrease < maxSustainedIncrease,
                "Memory usage under sustained load should be stable. Increase: " +
                        (memoryIncrease / 1024 / 1024) + "MB after " + requestCount + " requests");

        assertTrue(requestCount > 100, "Should process significant number of requests: " + requestCount);
    }

    @Test
    void testMemoryEfficiencyWithAllAgents() throws Exception {
        String[] domains = {
                "architecture", "implementation", "deployment", "testing", "security",
                "observability", "documentation", "business-domain", "integration-gateway",
                "pair-programming-navigator", "event-driven-architecture", "cicd-pipeline",
                "configuration-management", "resilience-engineering"
        };

        MemoryUsage beforeHeap = memoryBean.getHeapMemoryUsage();
        long beforeUsed = beforeHeap.getUsed();

        // Test all agents simultaneously
        List<CompletableFuture<AgentResponse>> futures = new ArrayList<>();

        for (int round = 0; round < 10; round++) {
            for (String domain : domains) {
                AgentRequest request = createTestRequest(domain, round);

                CompletableFuture<AgentResponse> future = CompletableFuture.supplyAsync(() -> {
                    return agentManager.processRequest(request);
                }, executorService);

                futures.add(future);
            }
        }

        // Wait for all requests to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(60, TimeUnit.SECONDS);

        // Force garbage collection
        System.gc();
        Thread.sleep(2000);

        MemoryUsage afterHeap = memoryBean.getHeapMemoryUsage();
        long afterUsed = afterHeap.getUsed();
        long memoryIncrease = afterUsed - beforeUsed;

        // Total memory for all agents should be within limits (< 2GB as per
        // requirements)
        long maxTotalMemory = 2L * 1024 * 1024 * 1024; // 2GB
        assertTrue(afterUsed < maxTotalMemory,
                "Total memory usage should be < 2GB. Actual: " + (afterUsed / 1024 / 1024) + "MB");

        // Memory increase for this test should be reasonable
        long maxTestIncrease = 500 * 1024 * 1024; // 500MB
        assertTrue(memoryIncrease < maxTestIncrease,
                "Memory increase should be reasonable. Actual: " + (memoryIncrease / 1024 / 1024) + "MB");

        // Verify all requests succeeded
        long successfulRequests = futures.stream()
                .mapToLong(future -> {
                    try {
                        AgentResponse response = future.get();
                        if (response == null) {
                            System.err.println("Response is null");
                            return 0;
                        }
                        if (!response.isSuccess()) {
                            System.err.println("Response failed: status=" + response.getStatus() +
                                    ", error=" + response.getErrorMessage());
                        }
                        return response.isSuccess() ? 1 : 0;
                    } catch (Exception e) {
                        System.err.println("Exception getting response: " + e.getMessage());
                        e.printStackTrace();
                        return 0;
                    }
                })
                .sum();

        assertEquals(futures.size(), successfulRequests, "All requests should succeed");
    }

    private AgentRequest createTestRequest(String domain, int index) {
        return AgentRequest.builder()
                .type("memory-test")
                .description("Memory usage test for " + domain + " domain, iteration " + index)
                .context(AgentContext.builder()
                        .domain(domain)
                        .property("testIndex", index)
                        .property("query", "Memory test query for " + domain + " iteration " + index)
                        .build())
                .securityContext(securityContext)
                .build();
    }
}

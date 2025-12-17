package com.pos.agent.performance;

import com.positivity.agent.controller.AgentConsultationController;
import com.positivity.agent.AgentConsultationRequest;
import com.positivity.agent.AgentGuidanceResponse;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

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

@SpringBootTest
@ActiveProfiles("test")
public class MemoryUsageTest {

    @Autowired
    private AgentConsultationController agentController;

    private MemoryMXBean memoryBean;
    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
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
        // Test memory usage for each agent type individually
        for (AgentType agentType : AgentType.values()) {
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
        MemoryUsage initialHeap = memoryBean.getHeapMemoryUsage();
        long initialUsed = initialHeap.getUsed();

        // Run multiple cycles of agent requests
        for (int cycle = 0; cycle < 5; cycle++) {
            List<CompletableFuture<AgentResponse>> futures = new ArrayList<>();

            // Create concurrent requests
            for (int i = 0; i < 20; i++) {
                AgentType agentType = AgentType.values()[i % AgentType.values().length];
                AgentRequest request = createTestRequest(agentType, i);

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
        MemoryUsage beforeHeap = memoryBean.getHeapMemoryUsage();
        long beforeUsed = beforeHeap.getUsed();

        // Sustained load for 30 seconds
        long endTime = System.currentTimeMillis() + 30000; // 30 seconds
        int requestCount = 0;

        while (System.currentTimeMillis() < endTime) {
            AgentType agentType = AgentType.values()[requestCount % AgentType.values().length];
            AgentRequest request = createTestRequest(agentType, requestCount);

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
        MemoryUsage beforeHeap = memoryBean.getHeapMemoryUsage();
        long beforeUsed = beforeHeap.getUsed();

        // Test all 15 agents simultaneously
        List<CompletableFuture<AgentResponse>> futures = new ArrayList<>();

        for (int round = 0; round < 10; round++) {
            for (AgentType agentType : AgentType.values()) {
                AgentRequest request = createTestRequest(agentType, round);

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
                        return response.isSuccess() ? 1 : 0;
                    } catch (Exception e) {
                        return 0;
                    }
                })
                .sum();

        assertEquals(futures.size(), successfulRequests, "All requests should succeed");
    }

    private AgentConsultationRequest createTestRequest(String domain, int index) {
        Map<String, Object> context = new HashMap<>();
        context.put("testIndex", index);

        String query = "Memory test query for " + domain + " iteration " + index;
        return AgentConsultationRequest.create(domain, query, context);
    }

    private String getDomain(int index) {
        String[] domains = {
                "architecture", "implementation", "deployment", "testing", "security",
                "observability", "documentation", "business-domain", "integration-gateway",
                "pair-programming-navigator", "event-driven-architecture", "cicd-pipeline",
                "configuration-management", "resilience-engineering"
        };
        return domains[index % domains.length];
    }
}

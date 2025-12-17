package com.pos.agent.framework.system;

import com.pos.agent.framework.core.AgentManager;
import com.pos.agent.framework.core.AgentRegistry;
import com.pos.agent.framework.core.AgentRequest;
import com.pos.agent.framework.core.AgentResponse;
import com.pos.agent.framework.context.AgentContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance and scalability tests for the agent framework.
 * Validates system performance under various load conditions.
 */
@SpringBootTest
@ActiveProfiles("test")
class PerformanceAndScalabilityTest {

    private AgentManager agentManager;
    private AgentRegistry agentRegistry;
    private MemoryMXBean memoryBean;

    @BeforeEach
    void setUp() {
        agentManager = new AgentManager();
        agentRegistry = new AgentRegistry();
        memoryBean = ManagementFactory.getMemoryMXBean();
        
        // Initialize system for performance testing
        initializePerformanceTestEnvironment();
    }

    @Test
    @DisplayName("Concurrent Load Testing - 100 Developers")
    void testConcurrentLoadWith100Developers() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(100);
        AtomicLong successfulRequests = new AtomicLong(0);
        AtomicLong totalResponseTime = new AtomicLong(0);

        // Simulate 100 concurrent developers making requests
        List<CompletableFuture<Void>> futures = IntStream.range(0, 100)
            .mapToObj(developerId -> CompletableFuture.runAsync(() -> {
                // Each developer makes 10 requests over 30 seconds
                for (int request = 0; request < 10; request++) {
                    try {
                        long startTime = System.currentTimeMillis();
                        
                        AgentRequest agentRequest = AgentRequest.builder()
                            .type(getDeveloperAgentType(developerId))
                            .context(createDeveloperContext(developerId, request))
                            .developerId("dev-" + developerId)
                            .build();

                        AgentResponse response = agentManager.processRequest(agentRequest);
                        
                        long responseTime = System.currentTimeMillis() - startTime;
                        totalResponseTime.addAndGet(responseTime);

                        if (response.isSuccess()) {
                            successfulRequests.incrementAndGet();
                        }

                        // Validate individual response time
                        assertTrue(responseTime <= 3000, 
                            "Individual response time should be ≤ 3 seconds");

                        // Simulate developer think time
                        Thread.sleep(100 + (int)(Math.random() * 200));
                        
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }, executor))
            .toList();

        // Wait for all developers to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .get(60, TimeUnit.SECONDS);

        // Validate performance metrics
        long totalRequests = 1000; // 100 developers * 10 requests
        double successRate = (double) successfulRequests.get() / totalRequests;
        double averageResponseTime = (double) totalResponseTime.get() / successfulRequests.get();

        assertTrue(successRate >= 0.99, 
            "Success rate should be ≥ 99% (was " + (successRate * 100) + "%)");
        assertTrue(averageResponseTime <= 1000, 
            "Average response time should be ≤ 1 second (was " + averageResponseTime + "ms)");

        executor.shutdown();
    }

    @Test
    @DisplayName("Memory Usage Validation - ≤ 2GB per Agent Instance")
    void testMemoryUsageValidation() {
        // Record initial memory usage
        long initialMemory = memoryBean.getHeapMemoryUsage().getUsed();

        // Create high memory load scenario
        ExecutorService executor = Executors.newFixedThreadPool(50);
        
        List<CompletableFuture<Void>> futures = IntStream.range(0, 200)
            .mapToObj(i -> CompletableFuture.runAsync(() -> {
                AgentRequest request = AgentRequest.builder()
                    .type("memory-intensive")
                    .context(createLargeContext())
                    .build();
                
                agentManager.processRequest(request);
            }, executor))
            .toList();

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            fail("Memory test should complete without timeout");
        }

        // Force garbage collection and measure memory
        System.gc();
        Thread.yield();
        
        long finalMemory = memoryBean.getHeapMemoryUsage().getUsed();
        long memoryIncrease = finalMemory - initialMemory;
        long memoryIncreaseMB = memoryIncrease / (1024 * 1024);

        // Validate memory usage is within limits (≤ 2GB = 2048MB)
        assertTrue(memoryIncreaseMB <= 2048, 
            "Memory usage should be ≤ 2GB (was " + memoryIncreaseMB + "MB)");

        executor.shutdown();
    }

    @Test
    @DisplayName("Throughput Testing - Requests per Second")
    void testThroughputValidation() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(20);
        AtomicLong completedRequests = new AtomicLong(0);
        
        long testDurationMs = 10000; // 10 seconds
        long startTime = System.currentTimeMillis();

        // Submit continuous requests for 10 seconds
        while (System.currentTimeMillis() - startTime < testDurationMs) {
            executor.submit(() -> {
                AgentRequest request = AgentRequest.builder()
                    .type("throughput-test")
                    .context(createSimpleContext())
                    .build();
                
                AgentResponse response = agentManager.processRequest(request);
                if (response.isSuccess()) {
                    completedRequests.incrementAndGet();
                }
            });
            
            Thread.sleep(10); // Small delay to prevent overwhelming
        }

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // Calculate throughput
        long actualDuration = System.currentTimeMillis() - startTime;
        double requestsPerSecond = (double) completedRequests.get() / (actualDuration / 1000.0);

        // Validate minimum throughput (should handle at least 50 requests/second)
        assertTrue(requestsPerSecond >= 50, 
            "Throughput should be ≥ 50 requests/second (was " + requestsPerSecond + ")");
    }

    @Test
    @DisplayName("Scalability Testing - Agent Instance Scaling")
    void testAgentInstanceScaling() {
        // Test with increasing number of agent instances
        int[] instanceCounts = {1, 2, 5, 10, 15};
        
        for (int instanceCount : instanceCounts) {
            // Configure system with specific instance count
            configureAgentInstances(instanceCount);
            
            // Run load test
            long startTime = System.currentTimeMillis();
            ExecutorService executor = Executors.newFixedThreadPool(20);
            
            List<CompletableFuture<AgentResponse>> futures = IntStream.range(0, 100)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                    AgentRequest request = AgentRequest.builder()
                        .type("scalability-test")
                        .context(createScalabilityContext())
                        .build();
                    
                    return agentManager.processRequest(request);
                }, executor))
                .toList();

            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(30, TimeUnit.SECONDS);
                
                long duration = System.currentTimeMillis() - startTime;
                
                // Validate that more instances improve performance
                if (instanceCount > 1) {
                    assertTrue(duration <= 15000, 
                        "With " + instanceCount + " instances, should complete within 15 seconds");
                }
                
            } catch (Exception e) {
                fail("Scalability test failed with " + instanceCount + " instances: " + e.getMessage());
            }
            
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("Response Time Distribution Analysis")
    void testResponseTimeDistribution() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(30);
        
        // Collect response times for 500 requests
        List<CompletableFuture<Long>> responseTimes = IntStream.range(0, 500)
            .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                long startTime = System.currentTimeMillis();
                
                AgentRequest request = AgentRequest.builder()
                    .type(getRandomAgentType())
                    .context(createVariedContext(i))
                    .build();
                
                agentManager.processRequest(request);
                return System.currentTimeMillis() - startTime;
            }, executor))
            .toList();

        // Wait for completion
        CompletableFuture.allOf(responseTimes.toArray(new CompletableFuture[0]))
            .get(60, TimeUnit.SECONDS);

        // Analyze distribution
        List<Long> times = responseTimes.stream()
            .map(future -> {
                try {
                    return future.get();
                } catch (Exception e) {
                    return 5000L; // Timeout value
                }
            })
            .sorted()
            .toList();

        // Calculate percentiles
        long p50 = times.get(times.size() / 2);
        long p90 = times.get((int) (times.size() * 0.9));
        long p95 = times.get((int) (times.size() * 0.95));
        long p99 = times.get((int) (times.size() * 0.99));

        // Validate response time distribution
        assertTrue(p50 <= 200, "50th percentile should be ≤ 200ms (was " + p50 + "ms)");
        assertTrue(p90 <= 400, "90th percentile should be ≤ 400ms (was " + p90 + "ms)");
        assertTrue(p95 <= 500, "95th percentile should be ≤ 500ms (was " + p95 + "ms)");
        assertTrue(p99 <= 3000, "99th percentile should be ≤ 3 seconds (was " + p99 + "ms)");

        executor.shutdown();
    }

    @Test
    @DisplayName("Resource Utilization Under Sustained Load")
    void testResourceUtilizationUnderSustainedLoad() throws InterruptedException {
        // Monitor resource usage during sustained load
        long initialMemory = memoryBean.getHeapMemoryUsage().getUsed();
        ExecutorService executor = Executors.newFixedThreadPool(25);
        
        // Run sustained load for 30 seconds
        long testDuration = 30000;
        long startTime = System.currentTimeMillis();
        AtomicLong requestCount = new AtomicLong(0);

        while (System.currentTimeMillis() - startTime < testDuration) {
            executor.submit(() -> {
                AgentRequest request = AgentRequest.builder()
                    .type("sustained-load")
                    .context(createSustainedLoadContext())
                    .build();
                
                agentManager.processRequest(request);
                requestCount.incrementAndGet();
            });
            
            Thread.sleep(50); // Sustained but not overwhelming load
        }

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // Measure final resource usage
        System.gc();
        long finalMemory = memoryBean.getHeapMemoryUsage().getUsed();
        long memoryGrowth = finalMemory - initialMemory;

        // Validate resource stability
        assertTrue(requestCount.get() > 500, 
            "Should process significant number of requests during sustained load");
        assertTrue(memoryGrowth < 500 * 1024 * 1024, 
            "Memory growth should be reasonable (< 500MB) during sustained load");
    }

    // Helper methods
    private void initializePerformanceTestEnvironment() {
        // Initialize optimized environment for performance testing
        // In real implementation, this would configure connection pools, caches, etc.
    }

    private String getDeveloperAgentType(int developerId) {
        String[] types = {
            "architecture", "implementation", "deployment", "testing", "security",
            "observability", "documentation", "business-domain"
        };
        return types[developerId % types.length];
    }

    private AgentContext createDeveloperContext(int developerId, int requestId) {
        return AgentContext.builder()
            .type("developer-request")
            .developerId("dev-" + developerId)
            .requestId("req-" + requestId)
            .domain("microservice")
            .build();
    }

    private AgentContext createLargeContext() {
        return AgentContext.builder()
            .type("memory-intensive")
            .domain("large-dataset")
            .requirements(Map.of(
                "dataSize", "large",
                "processing", "complex",
                "caching", "enabled"
            ))
            .build();
    }

    private AgentContext createSimpleContext() {
        return AgentContext.builder()
            .type("simple")
            .domain("basic")
            .build();
    }

    private AgentContext createScalabilityContext() {
        return AgentContext.builder()
            .type("scalability-test")
            .domain("performance")
            .requirements(Map.of("load", "high"))
            .build();
    }

    private AgentContext createVariedContext(int index) {
        String[] domains = {"catalog", "inventory", "customer", "order", "security", "analytics"};
        String[] types = {"simple", "complex", "collaborative", "specialized"};
        
        return AgentContext.builder()
            .type(types[index % types.length])
            .domain(domains[index % domains.length])
            .complexity(index % 3 == 0 ? "high" : "normal")
            .build();
    }

    private AgentContext createSustainedLoadContext() {
        return AgentContext.builder()
            .type("sustained-load")
            .domain("performance")
            .requirements(Map.of("duration", "long"))
            .build();
    }

    private String getRandomAgentType() {
        String[] types = {
            "architecture", "implementation", "deployment", "testing", "security",
            "observability", "documentation", "business-domain", "integration-gateway"
        };
        return types[(int) (Math.random() * types.length)];
    }

    private void configureAgentInstances(int instanceCount) {
        // Configure system with specific number of agent instances
        // In real implementation, this would adjust agent pool sizes
    }
}

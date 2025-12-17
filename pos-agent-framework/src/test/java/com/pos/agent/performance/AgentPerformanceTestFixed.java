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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class AgentPerformanceTestFixed {

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
    void testConcurrentAgentConsultationPerformance() throws Exception {
        // Test concurrent consultation requests to different agent domains
        int concurrentRequests = 100;
        List<CompletableFuture<AgentGuidanceResponse>> futures = new ArrayList<>();

        Instant startTime = Instant.now();

        for (int i = 0; i < concurrentRequests; i++) {
            String domain = getRandomDomain(i);
            AgentConsultationRequest request = createTestConsultationRequest(domain);

            CompletableFuture<AgentGuidanceResponse> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return agentController.consultAgent(request);
                } catch (Exception e) {
                    return AgentGuidanceResponse.failure(request.requestId(), domain,
                            "Consultation failed: " + e.getMessage(), Duration.ofMillis(0));
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

        // Check individual response times and success rate
        long responsesUnder500ms = 0;
        long successfulConsultations = 0;

        for (CompletableFuture<AgentGuidanceResponse> future : futures) {
            try {
                AgentGuidanceResponse response = future.get();
                if (response.processingTime().toMillis() <= 500) {
                    responsesUnder500ms++;
                }
                if (response.isSuccessful()) {
                    successfulConsultations++;
                }
            } catch (Exception e) {
                // Count as failure
            }
        }

        double percentageUnder500ms = (double) responsesUnder500ms / concurrentRequests * 100;
        assertTrue(percentageUnder500ms >= 95.0,
                "95% of agent responses should be ≤ 500ms. Actual: " + percentageUnder500ms + "%");

        double successPercentage = (double) successfulConsultations / concurrentRequests * 100;
        assertTrue(successPercentage >= 95.0,
                "95% of consultations should succeed. Actual: " + successPercentage + "%");
    }

    @Test
    void testAgentRegistryPerformance() {
        // Test agent discovery performance
        Instant startTime = Instant.now();

        for (int i = 0; i < 1000; i++) {
            String domain = getRandomDomain(i);
            Agent agent = agentRegistry.findAgentByDomain(domain);
            assertNotNull(agent, "Agent should be available for domain: " + domain);
        }

        Instant endTime = Instant.now();
        long discoveryTime = Duration.between(startTime, endTime).toMillis();

        // Agent discovery should be very fast (< 100ms for 1000 lookups)
        assertTrue(discoveryTime < 100,
                "Agent discovery should be fast. 1000 lookups took: " + discoveryTime + "ms");
    }

    @Test
    void testMemoryUsageUnderLoad() throws Exception {
        Runtime runtime = Runtime.getRuntime();
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();

        // Generate load with multiple concurrent requests
        int loadRequests = 200;
        List<CompletableFuture<AgentGuidanceResponse>> futures = new ArrayList<>();

        for (int i = 0; i < loadRequests; i++) {
            String domain = getRandomDomain(i);
            AgentConsultationRequest request = createTestConsultationRequest(domain);

            CompletableFuture<AgentGuidanceResponse> future = CompletableFuture.supplyAsync(() -> {
                return agentController.consultAgent(request);
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
    void testLoadBalancingPerformance() throws Exception {
        // Test load balancing across multiple consultation requests
        String testDomain = "architecture";
        int requests = 50;
        List<CompletableFuture<AgentGuidanceResponse>> futures = new ArrayList<>();

        Instant startTime = Instant.now();

        for (int i = 0; i < requests; i++) {
            AgentConsultationRequest request = createTestConsultationRequest(testDomain);

            CompletableFuture<AgentGuidanceResponse> future = CompletableFuture.supplyAsync(() -> {
                return agentController.consultAgent(request);
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
                        AgentGuidanceResponse response = future.get();
                        return response.isSuccessful() ? 1 : 0;
                    } catch (Exception e) {
                        return 0;
                    }
                })
                .sum();

        assertEquals(requests, successfulRequests, "All load balanced requests should succeed");
    }

    private String getRandomDomain(int index) {
        String[] domains = {
                "architecture", "implementation", "deployment", "testing", "security",
                "observability", "documentation", "business-domain", "integration-gateway",
                "pair-programming-navigator", "event-driven-architecture", "cicd-pipeline",
                "configuration-management", "resilience-engineering"
        };
        return domains[index % domains.length];
    }

    private AgentConsultationRequest createTestConsultationRequest(String domain) {
        Map<String, Object> context = new HashMap<>();
        context.put("service", "test-service");
        context.put("environment", "test");

        String query = switch (domain) {
            case "architecture" -> "Design microservice architecture for inventory service";
            case "implementation" -> "Implement REST controller for product management";
            case "deployment" -> "Configure Kubernetes deployment for order service";
            case "testing" -> "Create unit tests for customer service";
            case "security" -> "Implement JWT authentication for API gateway";
            case "observability" -> "Configure monitoring for payment service";
            case "documentation" -> "Generate API documentation for catalog service";
            case "business-domain" -> "Validate business rules for pricing service";
            case "integration-gateway" -> "Configure API gateway routing for inventory";
            case "pair-programming-navigator" -> "Review code quality for order processing";
            case "event-driven-architecture" -> "Design event schema for order events";
            case "cicd-pipeline" -> "Configure CI/CD pipeline for customer service";
            case "configuration-management" -> "Manage configuration for payment service";
            case "resilience-engineering" -> "Implement circuit breaker for external API";
            default -> "Generic performance test query for " + domain;
        };

        return AgentConsultationRequest.create(domain, query, context);
    }
}
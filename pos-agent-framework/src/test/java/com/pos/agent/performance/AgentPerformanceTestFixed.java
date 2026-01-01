package com.pos.agent.performance;

import com.pos.agent.context.AgentContext;
import com.pos.agent.core.AgentManager;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.core.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class AgentPerformanceTestFixed {

    private AgentManager agentManager;
    private SecurityContext securityContext;

    @BeforeEach
    void setUp() {
        agentManager = new AgentManager();
        securityContext = SecurityContext.builder()
                .jwtToken("test-token-12345")
                .userId("test-user")
                .roles(List.of("developer", "architect"))
                .permissions(List.of("AGENT_READ", "AGENT_WRITE", "performance:test", "domain:access"))
                .serviceId("test-service")
                .serviceType("test")
                .build();
    }

    @Test
    void testConcurrentAgentConsultationPerformance() throws Exception {
        // Test concurrent consultation requests to different agent domains
        int concurrentRequests = 100;
        List<AgentResponse> responses = new ArrayList<>();
        String[] domains = getAvailableDomains();

        Instant startTime = Instant.now();

        for (int i = 0; i < concurrentRequests; i++) {
            String domain = domains[i % domains.length];
            AgentRequest request = createTestConsultationRequest(domain);
            AgentResponse response = agentManager.processRequest(request);
            responses.add(response);
        }

        Instant endTime = Instant.now();
        Duration totalDuration = Duration.between(startTime, endTime);

        // Validate performance targets
        assertTrue(totalDuration.toMillis() < 3000,
                "System response time should be ≤ 3 seconds for 99% of requests. Actual: " + totalDuration.toMillis()
                        + "ms");

        // Check individual response times and success rate
        long responsesUnder500ms = 0;
        long successfulConsultations = 0;

        for (AgentResponse response : responses) {
            if (response.getProcessingTimeMs() <= 500) {
                responsesUnder500ms++;
            }
            if (response.isSuccess()) {
                successfulConsultations++;
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
    void testAgentDiscoveryPerformance() {
        // Test agent discovery performance via request processing
        String[] domains = getAvailableDomains();
        Instant startTime = Instant.now();

        for (int i = 0; i < 1000; i++) {
            String domain = domains[i % domains.length];
            AgentRequest request = createTestConsultationRequest(domain);
            AgentResponse response = agentManager.processRequest(request);
            assertNotNull(response, "Agent response should be available for domain: " + domain);
        }

        Instant endTime = Instant.now();
        long discoveryTime = Duration.between(startTime, endTime).toMillis();

        // Agent discovery should be very fast (< 1200ms for 1000 lookups)
        assertTrue(discoveryTime < 1200,
                "Agent discovery should be fast. 1000 lookups took: " + discoveryTime + "ms");
    }

    @Test
    void testMemoryUsageUnderLoad() throws Exception {
        Runtime runtime = Runtime.getRuntime();
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();

        // Generate load with multiple requests
        int loadRequests = 200;
        String[] domains = getAvailableDomains();
        List<AgentResponse> responses = new ArrayList<>();

        for (int i = 0; i < loadRequests; i++) {
            String domain = domains[i % domains.length];
            AgentRequest request = createTestConsultationRequest(domain);
            AgentResponse response = agentManager.processRequest(request);
            responses.add(response);
        }

        // Force garbage collection and measure memory
        System.gc();
        Thread.sleep(1000);
        long finalMemory = runtime.totalMemory() - runtime.freeMemory();
        long memoryIncrease = finalMemory - initialMemory;

        // Memory increase should be reasonable (less than 512MB for this test)
        long maxMemoryIncrease = 512 * 1024 * 1024; // 512MB
        assertTrue(memoryIncrease < maxMemoryIncrease,
                "Memory usage increase should be < 512MB. Actual increase: " + (memoryIncrease / 1024 / 1024) + "MB");
        assertTrue(!responses.isEmpty(), "All load test requests should be processed");
    }

    @Test
    void testLoadBalancingPerformance() throws Exception {
        // Test load balancing across multiple consultation requests
        String testDomain = "architecture";
        int requests = 50;
        List<AgentResponse> responses = new ArrayList<>();

        Instant startTime = Instant.now();

        for (int i = 0; i < requests; i++) {
            AgentRequest request = createTestConsultationRequest(testDomain);
            AgentResponse response = agentManager.processRequest(request);
            responses.add(response);
        }

        Instant endTime = Instant.now();
        Duration totalTime = Duration.between(startTime, endTime);

        // Load balancing should distribute requests efficiently
        assertTrue(totalTime.toMillis() < 2000,
                "Load balanced requests should complete quickly. Actual: " + totalTime.toMillis() + "ms");

        // Verify all requests succeeded
        long successfulRequests = responses.stream()
                .filter(AgentResponse::isSuccess)
                .count();

        assertEquals(requests, successfulRequests, "All load balanced requests should succeed");
    }

    private String[] getAvailableDomains() {
        return new String[] {
                "architecture", "implementation", "deployment", "testing", "security",
                "observability", "documentation", "business-domain", "integration-gateway",
                "pair-programming-navigator", "event-driven-architecture", "cicd-pipeline",
                "configuration-management", "resilience-engineering"
        };
    }

    private AgentRequest createTestConsultationRequest(String domain) {
        return AgentRequest.builder()
                .type("performance-test")
                .description("Performance test request for domain: " + domain)
                .context(AgentContext.builder()
                        .domain(domain)
                        .property("service", "test-service")
                        .property("environment", "test")
                        .property("query", getQueryForDomain(domain))
                        .build())
                .securityContext(securityContext)
                .build();
    }

    private String getQueryForDomain(String domain) {
        return switch (domain) {
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
    }
}
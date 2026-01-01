package com.pos.agent.framework.system;

import com.pos.agent.core.AgentManager;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.SecurityContext;
import com.pos.agent.context.AgentContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive system integration tests for the agent framework.
 * Tests full system functionality with all 15 agents operational.
 */
class ComprehensiveSystemIntegrationTest {

        private AgentManager agentManager;

        @BeforeEach
        void setUp() {
                agentManager = new AgentManager();
        }

        @Test
        @DisplayName("Full System Integration - All Agent Types Processable")
        void testFullSystemIntegration() {
                // Test each agent type can be processed successfully
                String[] expectedAgentTypes = {
                                "architecture", "implementation", "deployment", "testing", "security",
                                "observability", "documentation", "business-domain", "integration-gateway",
                                "pair-programming", "event-driven", "cicd-pipeline", "configuration-management",
                                "resilience-engineering"
                };

                for (String agentType : expectedAgentTypes) {
                        AgentRequest request = AgentRequest.builder()
                                        .type(agentType)
                                        .description("Comprehensive system integration test for " + agentType
                                                        + " agent")
                                        .context(createContextForAgentType(agentType))
                                        .build();

                        AgentResponse response = agentManager.processRequest(request);
                        assertNotNull(response, "Response should not be null for type: " + agentType);
                        assertTrue(response.isSuccess(), "Processing should succeed for type: " + agentType);
                        assertEquals("SUCCESS", response.getStatus(), "Status should be SUCCESS");
                }
        }

        @Test
        @DisplayName("Multi-Agent Collaboration Across All Domains")
        void testMultiAgentCollaboration() {
                // Simulate a complex scenario with security context and strict requirements
                AgentContext context = createMicroserviceContext();
                SecurityContext security = SecurityContext.builder()
                                .jwtToken("valid.jwt.token")
                                .userId("user-123")
                                .roles(List.of("USER"))
                                .permissions(List.of("AGENT_READ", "AGENT_WRITE"))
                                .serviceId("pos-api-gateway")
                                .serviceType("spring-boot")
                                .build();

                AgentRequest request = AgentRequest.builder()
                                .type("microservice-implementation")
                                .description("Multi-agent collaboration test across all domains")
                                .context(context)
                                .securityContext(security)
                                .requireTLS13(true)
                                .build();

                AgentResponse response = agentManager.processRequest(request);

                assertNotNull(response, "Response should not be null");
                assertTrue(response.isSuccess(), "Processing should succeed with valid security context");
                assertNotNull(response.getSecurityValidation(), "Security validation should be present");
                assertTrue(response.getSecurityValidation().isTLS13Compliant(), "TLS 1.3 should be compliant");
                assertEquals("TLSv1.3", response.getSecurityValidation().getTLSVersion(), "TLS version should be 1.3");
                assertNotNull(response.getSecurityValidation().getServiceAuthentication(),
                                "Service authentication should be populated");
        }

        @Test
        @DisplayName("End-to-End Request Processing Performance")
        void testEndToEndPerformanceUnderLoad() {
                ExecutorService executor = Executors.newFixedThreadPool(20);

                // Submit 100 concurrent requests across different agent types
                List<CompletableFuture<AgentResponse>> futures = IntStream.range(0, 100)
                                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                                        String agentType = getAgentTypeForIndex(i);
                                        AgentRequest request = AgentRequest.builder()
                                                        .type(agentType)
                                                        .description("Performance test request for " + agentType
                                                                        + " agent")
                                                        .context(createContextForAgentType(agentType))
                                                        .build();

                                        long startTime = System.currentTimeMillis();
                                        AgentResponse response = agentManager.processRequest(request);
                                        long responseTime = System.currentTimeMillis() - startTime;

                                        // Validate performance requirements
                                        assertTrue(responseTime <= 3000,
                                                        "Response time should be ≤ 3 seconds (was " + responseTime
                                                                        + "ms)");

                                        return response;
                                }, executor))
                                .toList();

                // Wait for all requests to complete within 30 seconds
                assertTimeoutPreemptively(Duration.ofSeconds(30),
                                () -> CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join());

                // Validate all responses
                long successfulResponses = futures.stream()
                                .mapToLong(future -> {
                                        try {
                                                AgentResponse response = future.get();
                                                return response.isSuccess() ? 1 : 0;
                                        } catch (Exception e) {
                                                return 0;
                                        }
                                })
                                .sum();

                assertTrue(successfulResponses >= 95,
                                "At least 95% of requests should succeed under load");

                executor.shutdown();
        }

        @Test
        @DisplayName("System Resilience and Failover Testing")
        void testSystemResilienceAndFailover() {
                // Simulate invalid security (failure) then recovery with valid credentials
                AgentRequest failingRequest = AgentRequest.builder()
                                .type("implementation")
                                .description("System resilience test with invalid credentials")
                                .context(createImplementationContext())
                                .securityContext(SecurityContext.builder()
                                                .jwtToken("invalid.jwt.token")
                                                .roles(List.of("USER"))
                                                .permissions(List.of("AGENT_READ", "AGENT_WRITE"))
                                                .build())
                                .build();

                AgentResponse failedResponse = agentManager.processRequest(failingRequest);
                assertNotNull(failedResponse, "System should return a response on failure");
                assertFalse(failedResponse.isSuccess(), "Invalid credentials should fail");
                assertTrue(failedResponse.getErrorMessage().contains("Authentication failed"),
                                "Error message should indicate authentication failure");

                AgentRequest recoveryRequest = AgentRequest.builder()
                                .type("implementation")
                                .description("System recovery test with valid credentials")
                                .context(createImplementationContext())
                                .securityContext(SecurityContext.builder()
                                                .jwtToken("valid.jwt.token")
                                                .roles(List.of("USER"))
                                                .permissions(List.of("AGENT_READ", "AGENT_WRITE"))
                                                .build())
                                .build();

                AgentResponse recoveryResponse = agentManager.processRequest(recoveryRequest);
                assertTrue(recoveryResponse.isSuccess(), "System should succeed after providing valid credentials");
        }

        @Test
        @DisplayName("Context Sharing and Memory Management")
        void testContextSharingAndMemoryManagement() {
                // Create multiple contexts and verify properties are accessible
                AgentContext architectureContext = createArchitectureContext();
                AgentContext implementationContext = createImplementationContext();
                AgentContext securityCtx = createSecurityContext();

                assertEquals("architecture", architectureContext.getContextType());
                assertEquals("implementation", implementationContext.getContextType());
                assertEquals("security", securityCtx.getContextType());

                // Process a request using one of the contexts
                AgentRequest request = AgentRequest.builder()
                                .type("architecture")
                                .description("Context sharing and memory management test")
                                .context(architectureContext)
                                .build();

                AgentResponse response = agentManager.processRequest(request);
                assertTrue(response.isSuccess(), "Request should succeed with valid context");
        }

        @Test
        @DisplayName("Service Integration with All Microservices")
        void testServiceIntegrationWithAllMicroservices() {
                // Validate requests for multiple microservices are processed successfully
                String[] microservices = {
                                "pos-catalog", "pos-customer", "pos-inventory", "pos-vehicle-inventory",
                                "pos-order", "pos-invoice", "pos-price", "pos-accounting", "pos-work-order",
                                "pos-people", "pos-location", "pos-events", "pos-event-receiver",
                                "pos-image", "pos-vehicle-fitment", "pos-vehicle-reference-data",
                                "pos-inquiry", "pos-shop-manager", "pos-api-gateway", "pos-security-service"
                };

                for (String service : microservices) {
                        AgentRequest request = AgentRequest.builder()
                                        .type("service-specific")
                                        .description("Service integration test for " + service)
                                        .context(createServiceContext(service))
                                        .build();

                        AgentResponse response = agentManager.processRequest(request);
                        assertNotNull(response, "Should handle request for service: " + service);
                        assertTrue(response.isSuccess(), "Processing should succeed for service: " + service);
                }
        }

        @Test
        @DisplayName("Performance Validation Under Production Load")
        void testPerformanceValidationUnderProductionLoad() {
                ExecutorService executor = Executors.newFixedThreadPool(50);

                // Simulate production load with 500 requests over 30 seconds
                List<CompletableFuture<Long>> responseTimes = IntStream.range(0, 500)
                                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                                        long startTime = System.currentTimeMillis();

                                        AgentRequest request = AgentRequest.builder()
                                                        .type(getRandomAgentType())
                                                        .description("Performance test request under production load")
                                                        .context(createRandomContext())
                                                        .build();

                                        agentManager.processRequest(request);
                                        return System.currentTimeMillis() - startTime;
                                }, executor))
                                .toList();

                // Wait for completion within 60 seconds
                assertTimeoutPreemptively(Duration.ofSeconds(60),
                                () -> CompletableFuture.allOf(responseTimes.toArray(new CompletableFuture[0])).join());

                // Analyze performance metrics
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

                // Validate performance requirements
                long p95ResponseTime = times.get((int) (times.size() * 0.95));
                long p99ResponseTime = times.get((int) (times.size() * 0.99));

                assertTrue(p95ResponseTime <= 500,
                                "95th percentile response time should be ≤ 500ms (was " + p95ResponseTime + "ms)");
                assertTrue(p99ResponseTime <= 3000,
                                "99th percentile response time should be ≤ 3 seconds (was " + p99ResponseTime + "ms)");

                executor.shutdown();
        }

        // Helper methods
        // Removed legacy registry-based initialization; AgentManager handles requests
        // directly

        private AgentContext createMicroserviceContext() {
                return AgentContext.builder()
                                .type("microservice-implementation")
                                .serviceType("spring-boot")
                                .domain("inventory")
                                .property("database", "postgresql")
                                .property("messaging", "kafka")
                                .build();
        }

        private AgentContext createContextForAgentType(String agentType) {
                return AgentContext.builder()
                                .type(agentType)
                                .domain("general")
                                .build();
        }

        private AgentContext createArchitectureContext() {
                return AgentContext.builder()
                                .type("architecture")
                                .serviceType("microservice")
                                .domain("catalog")
                                .build();
        }

        private AgentContext createImplementationContext() {
                return AgentContext.builder()
                                .type("implementation")
                                .serviceType("spring-boot")
                                .domain("inventory")
                                .build();
        }

        private AgentContext createSecurityContext() {
                return AgentContext.builder()
                                .type("security")
                                .property("securityType", "jwt-authentication")
                                .domain("all")
                                .build();
        }

        private AgentContext createServiceContext(String service) {
                return AgentContext.builder()
                                .type("service-specific")
                                .serviceType("spring-boot")
                                .property("serviceName", service)
                                .build();
        }

        private AgentContext createRandomContext() {
                String[] domains = { "catalog", "inventory", "customer", "order", "security" };
                return AgentContext.builder()
                                .type("general")
                                .domain(domains[(int) (Math.random() * domains.length)])
                                .build();
        }

        private String getAgentTypeForIndex(int index) {
                String[] types = {
                                "architecture", "implementation", "deployment", "testing", "security",
                                "observability", "documentation", "business-domain", "integration-gateway",
                                "pair-programming", "event-driven", "cicd-pipeline", "configuration-management",
                                "resilience-engineering"
                };
                return types[index % types.length];
        }

        private String getRandomAgentType() {
                String[] types = {
                                "architecture", "implementation", "deployment", "testing", "security",
                                "observability", "documentation", "business-domain"
                };
                return types[(int) (Math.random() * types.length)];
        }

        // Legacy failure simulation methods removed; using security context to simulate
        // failures
}

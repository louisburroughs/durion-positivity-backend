package com.pos.agent.integration;

import com.pos.agent.core.AgentManager;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.core.SecurityContext;
import com.pos.agent.context.AgentContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive system integration tests aligned with available core/context
 * APIs.
 */
class ComprehensiveSystemIntegrationTest {

        private AgentManager agentManager;
        private ExecutorService executorService;

        @BeforeEach
        void setUp() {
                agentManager = new AgentManager();
                executorService = Executors.newFixedThreadPool(10);
        }

        @Test
        @DisplayName("All Agent Types Process Successfully")
        void testAllAgentsOperational() {
                String[] expectedAgentTypes = {
                                "architecture", "implementation", "deployment", "testing", "security",
                                "observability", "documentation", "business-domain", "integration-gateway",
                                "pair-programming", "event-driven", "cicd-pipeline",
                                "configuration-management", "resilience-engineering"
                };

                SecurityContext security = SecurityContext.builder()
                                .jwtToken("comprehensive-system-jwt-token")
                                .userId("test-user")
                                .serviceId("test-service")
                                .serviceType("test")
                                .build();

                for (String agentType : expectedAgentTypes) {
                        AgentRequest request = AgentRequest.builder()
                                        .type(agentType)
                                        .context(createContextForAgent(agentType))
                                        .securityContext(security)
                                        .build();

                        AgentResponse response = agentManager.processRequest(request);
                        assertNotNull(response, "Response should not be null for type: " + agentType);
                        assertTrue(response.isSuccess(), "Processing should succeed for type: " + agentType);
                        assertEquals("SUCCESS", response.getStatus());
                }
        }

        @Test
        @DisplayName("Multi-Agent Collaboration Scenarios")
        void testMultiAgentCollaborationScenarios() {
                // Architecture scenario
                AgentRequest architectureRequest = AgentRequest.builder()
                                .type("architecture")
                                .context(AgentContext.builder()
                                                .serviceType("microservice")
                                                .domain("inventory")
                                                .property("scalabilityRequirements", "high")
                                                .build())
                                .build();

                AgentResponse architectureResponse = agentManager.processRequest(architectureRequest);
                assertNotNull(architectureResponse);
                assertTrue(architectureResponse.isSuccess());

                // Security scenario with validation
                SecurityContext security = SecurityContext.builder()
                                .jwtToken("valid.jwt.token")
                                .userId("user-123")
                                .serviceId("pos-api-gateway")
                                .serviceType("spring-boot")
                                .build();

                AgentRequest securityRequest = AgentRequest.builder()
                                .type("security")
                                .context(AgentContext.builder()
                                                .domain("all")
                                                .property("securityRequirements", List.of("encryption", "audit"))
                                                .build())
                                .securityContext(security)
                                .requireTLS13(true)
                                .build();

                AgentResponse securityResponse = agentManager.processRequest(securityRequest);
                assertNotNull(securityResponse);
                assertTrue(securityResponse.isSuccess());
                assertNotNull(securityResponse.getSecurityValidation());
                assertTrue(securityResponse.getSecurityValidation().isTLS13Compliant());
                assertEquals("TLSv1.3", securityResponse.getSecurityValidation().getTLSVersion());

                // Event-driven scenario
                AgentRequest eventRequest = AgentRequest.builder()
                                .type("event-driven")
                                .context(AgentContext.builder()
                                                .property("eventType", "domain-event")
                                                .property("messagingPlatform", "kafka")
                                                .property("consistencyModel", "eventual")
                                                .build())
                                .securityContext(security)
                                .build();

                AgentResponse eventResponse = agentManager.processRequest(eventRequest);
                assertNotNull(eventResponse);
                assertTrue(eventResponse.isSuccess());
        }

        @Test
        @DisplayName("Concurrent Agent Requests")
        void testConcurrentAgentRequests() {
                SecurityContext security = SecurityContext.builder()
                                .jwtToken("concurrent-requests-jwt-token")
                                .userId("test-user")
                                .serviceId("test-service")
                                .serviceType("test")
                                .build();

                List<CompletableFuture<AgentResponse>> futures = IntStream.range(0, 50)
                                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                                        String agentType = getAgentTypeForIndex(i);
                                        AgentRequest request = AgentRequest.builder()
                                                        .type(agentType)
                                                        .context(createContextForAgent(agentType))
                                                        .securityContext(security)
                                                        .build();
                                        return agentManager.processRequest(request);
                                }, executorService))
                                .toList();

                assertTimeoutPreemptively(Duration.ofSeconds(30),
                                () -> CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join());

                futures.forEach(f -> {
                        try {
                                AgentResponse response = f.get();
                                assertNotNull(response);
                                assertTrue(response.isSuccess());
                        } catch (Exception e) {
                                fail("Concurrent request failed: " + e.getMessage());
                        }
                });
        }

        @Test
        @DisplayName("Performance Under Load")
        void testPerformanceUnderLoad() {
                SecurityContext security = SecurityContext.builder()
                                .jwtToken("performance-under-load-jwt-token")
                                .userId("test-user")
                                .serviceId("test-service")
                                .serviceType("test")
                                .build();

                long startTime = System.currentTimeMillis();

                List<CompletableFuture<AgentResponse>> futures = IntStream.range(0, 100)
                                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                                        AgentRequest request = AgentRequest.builder()
                                                        .type("implementation")
                                                        .context(AgentContext.builder()
                                                                        .serviceType("microservice")
                                                                        .property("framework", "spring-boot")
                                                                        .build())
                                                        .securityContext(security)
                                                        .build();
                                        return agentManager.processRequest(request);
                                }, executorService))
                                .toList();

                assertTimeoutPreemptively(Duration.ofSeconds(60),
                                () -> CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join());

                long totalTime = System.currentTimeMillis() - startTime;
                assertTrue(totalTime < 60000, "100 requests should complete within 60 seconds");

                futures.forEach(f -> {
                        try {
                                AgentResponse response = f.get();
                                assertNotNull(response);
                                assertTrue(response.isSuccess());
                        } catch (Exception e) {
                                fail("Load request failed: " + e.getMessage());
                        }
                });
        }

        @Test
        @DisplayName("Agent Failover via Invalid Credentials")
        void testAgentFailoverScenarios() {
                AgentRequest request = AgentRequest.builder()
                                .type("implementation")
                                .context(AgentContext.builder().build())
                                .securityContext(SecurityContext.builder().jwtToken("invalid.jwt.token").build())
                                .build();

                AgentResponse response = agentManager.processRequest(request);
                assertNotNull(response);
                assertFalse(response.isSuccess());
                assertNotNull(response.getErrorMessage());
        }

        @Test
        @DisplayName("Cross Domain Collaboration")
        void testCrossDomainCollaboration() {
                AgentRequest businessRequest = AgentRequest.builder()
                                .type("business-domain")
                                .context(AgentContext.builder()
                                                .domain("automotive")
                                                .property("businessProcess", "vehicle-fitment")
                                                .build())
                                .build();

                AgentResponse businessResponse = agentManager.processRequest(businessRequest);
                assertNotNull(businessResponse);
                assertTrue(businessResponse.isSuccess());

                AgentRequest cicdRequest = AgentRequest.builder()
                                .type("cicd-pipeline")
                                .context(AgentContext.builder()
                                                .property("pipelineType", "deployment")
                                                .property("targetEnvironment", "production")
                                                .property("securityScanning", true)
                                                .build())
                                .build();

                AgentResponse cicdResponse = agentManager.processRequest(cicdRequest);
                assertNotNull(cicdResponse);
                assertTrue(cicdResponse.isSuccess());
        }

        @Test
        @DisplayName("Configuration Management Integration")
        void testConfigurationManagementIntegration() {
                AgentRequest configRequest = AgentRequest.builder()
                                .type("configuration-management")
                                .context(AgentContext.builder()
                                                .property("configurationType", "application")
                                                .property("environment", "production")
                                                .property("secretsManagement", true)
                                                .build())
                                .build();

                AgentResponse configResponse = agentManager.processRequest(configRequest);
                assertNotNull(configResponse);
                assertTrue(configResponse.isSuccess());
        }

        @Test
        @DisplayName("Resilience Pattern Integration")
        void testResiliencePatternIntegration() {
                AgentRequest resilienceRequest = AgentRequest.builder()
                                .type("resilience-engineering")
                                .context(AgentContext.builder()
                                                .property("resiliencePattern", "circuit-breaker")
                                                .property("targetService", "pos-inventory")
                                                .property("failureThreshold", 5)
                                                .build())
                                .build();

                AgentResponse resilienceResponse = agentManager.processRequest(resilienceRequest);
                assertNotNull(resilienceResponse);
                assertTrue(resilienceResponse.isSuccess());
        }

        private String getAgentTypeForIndex(int index) {
                String[] agentTypes = {
                                "architecture", "implementation", "deployment", "testing", "security",
                                "observability", "documentation", "business-domain", "integration-gateway",
                                "pair-programming", "event-driven", "cicd-pipeline",
                                "configuration-management", "resilience-engineering"
                };
                return agentTypes[index % agentTypes.length];
        }

        private AgentContext createContextForAgent(String agentType) {
                return AgentContext.builder()
                                .type(agentType)
                                .domain("test")
                                .build();
        }
}

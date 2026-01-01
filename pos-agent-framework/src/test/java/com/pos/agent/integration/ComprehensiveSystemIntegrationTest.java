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
                                .roles(List.of("USER"))
                                .permissions(List.of("AGENT_READ", "AGENT_WRITE"))
                                .serviceId("test-service")
                                .serviceType("test")
                                .build();

                // Special security context for configuration-management (requires elevated
                // permissions)
                SecurityContext configSecurity = SecurityContext.builder()
                                .jwtToken("comprehensive-system-jwt-token")
                                .userId("test-user")
                                .roles(List.of("USER", "CONFIG_MANAGER"))
                                .permissions(List.of("AGENT_READ", "AGENT_WRITE", "CONFIG_MANAGE", "SECRETS_MANAGE"))
                                .serviceId("test-service")
                                .serviceType("test")
                                .build();

                for (String agentType : expectedAgentTypes) {
                        // Use special security context for configuration-management
                        SecurityContext contextToUse = agentType.equals("configuration-management") ? configSecurity
                                        : security;

                        AgentRequest request = AgentRequest.builder()
                                        .type(agentType)
                                        .description("Comprehensive test for agent type: " + agentType)
                                        .context(createContextForAgent(agentType))
                                        .securityContext(contextToUse)
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
                                .description("Architecture guidance for scalable microservice design")
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
                                .roles(List.of("USER"))
                                .permissions(List.of("AGENT_READ", "AGENT_WRITE"))
                                .serviceId("pos-api-gateway")
                                .serviceType("spring-boot")
                                .build();

                AgentRequest securityRequest = AgentRequest.builder()
                                .type("security")
                                .description("Security validation for encryption and audit requirements")
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
                                .description("Event-driven architecture for domain events handling")
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
                                .roles(List.of("USER"))
                                .permissions(List.of("AGENT_READ", "AGENT_WRITE"))
                                .serviceId("test-service")
                                .serviceType("test")
                                .build();

                // Special security context for configuration-management (requires elevated
                // permissions)
                SecurityContext configSecurity = SecurityContext.builder()
                                .jwtToken("concurrent-requests-jwt-token")
                                .userId("test-user")
                                .roles(List.of("USER", "CONFIG_MANAGER"))
                                .permissions(List.of("AGENT_READ", "AGENT_WRITE", "CONFIG_MANAGE", "SECRETS_MANAGE"))
                                .serviceId("test-service")
                                .serviceType("test")
                                .build();

                List<CompletableFuture<AgentResponse>> futures = IntStream.range(0, 50)
                                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                                        String agentType = getAgentTypeForIndex(i);
                                        // Use special security context for configuration-management
                                        SecurityContext contextToUse = agentType.equals("configuration-management")
                                                        ? configSecurity
                                                        : security;

                                        AgentRequest request = AgentRequest.builder()
                                                        .type(agentType)
                                                        .description("Concurrent request test for agent type: "
                                                                        + agentType)
                                                        .context(createContextForAgent(agentType))
                                                        .securityContext(contextToUse)
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
                                .roles(List.of("USER"))
                                .permissions(List.of("AGENT_READ", "AGENT_WRITE"))
                                .serviceId("test-service")
                                .serviceType("test")
                                .build();

                long startTime = System.currentTimeMillis();

                List<CompletableFuture<AgentResponse>> futures = IntStream.range(0, 100)
                                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                                        String agentType = getAgentTypeForIndex(i);
                                        // Use special security context for configuration-management
                                        SecurityContext contextToUse = agentType.equals("configuration-management")
                                                        ? SecurityContext.builder()
                                                                        .jwtToken("performance-under-load-jwt-token")
                                                                        .userId("test-user")
                                                                        .roles(List.of("USER", "CONFIG_MANAGER"))
                                                                        .permissions(List.of("AGENT_READ",
                                                                                        "AGENT_WRITE", "CONFIG_MANAGE",
                                                                                        "SECRETS_MANAGE"))
                                                                        .serviceId("test-service")
                                                                        .serviceType("test")
                                                                        .build()
                                                        : security;

                                        AgentRequest request = AgentRequest.builder()
                                                        .type(agentType)
                                                        .description("Load test request " + (i + 1)
                                                                        + " for agent type: " + agentType)
                                                        .context(AgentContext.builder()
                                                                        .type(agentType)
                                                                        .serviceType("microservice")
                                                                        .property("framework", "spring-boot")
                                                                        .build())
                                                        .securityContext(contextToUse)
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
                                .description("Failover test with invalid security credentials")
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
                                .description("Business domain collaboration for vehicle fitment process")
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
                                .description("CI/CD pipeline integration for automotive deployment")
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
                                .description("Configuration management for production application settings")
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
                                .description("Resilience pattern implementation for inventory service")
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

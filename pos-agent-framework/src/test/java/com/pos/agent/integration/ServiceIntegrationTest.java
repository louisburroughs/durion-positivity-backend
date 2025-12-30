package com.pos.agent.integration;

import com.pos.agent.core.AgentManager;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.core.SecurityContext;
import com.pos.agent.context.AgentContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Service integration tests aligned with available AgentManager and
 * AgentContext APIs.
 */
class ServiceIntegrationTest {

        private AgentManager agentManager;
        private SecurityContext securityContext;

        @BeforeEach
        void setUp() {
                agentManager = new AgentManager();
                securityContext = SecurityContext.builder()
                                .jwtToken("service-integration-jwt-token")
                                .userId("test-user")
                                .serviceId("test-service")
                                .serviceType("test")
                                //.securityContext(securityContext)
                                .build();
        }

        @Test
        @DisplayName("Event-driven integrates with Kafka messaging services")
        void testEventDrivenAgentKafkaIntegration() {
                AgentRequest request = AgentRequest.builder()
                                .type("event-driven")
                                .context(AgentContext.builder()
                                                .property("eventType", "OrderCreated")
                                                .property("serviceName", "pos-order")
                                                .property("messagingPlatform", "kafka")
                                                .build())
                                .securityContext(securityContext)
                                .build();

                AgentResponse response = agentManager.processRequest(request);
                assertNotNull(response);
                assertTrue(response.isSuccess());
        }

        @Test
        @DisplayName("CI/CD integrates with AWS deployment")
        void testCICDAgentAWSIntegration() {
                AgentRequest request = AgentRequest.builder()
                                .type("cicd-pipeline")
                                .context(AgentContext.builder()
                                                .property("serviceName", "pos-inventory")
                                                .property("deploymentTarget", "AWS")
                                                .property("environment", "production")
                                                .build())
                                .securityContext(securityContext)
                                .build();

                AgentResponse response = agentManager.processRequest(request);
                assertNotNull(response);
                assertTrue(response.isSuccess());
        }

        @Test
        @DisplayName("Configuration integrates with Spring Cloud Config")
        void testConfigurationAgentSpringCloudIntegration() {
                AgentRequest request = AgentRequest.builder()
                                .type("configuration-management")
                                .context(AgentContext.builder()
                                                .property("serviceName", "pos-catalog")
                                                .property("configurationType", "database")
                                                .property("environment", "development")
                                                .build())
                                .securityContext(securityContext)
                                .build();

                AgentResponse response = agentManager.processRequest(request);
                assertNotNull(response);
                assertTrue(response.isSuccess());
        }

        @Test
        @DisplayName("Resilience integrates with Kubernetes deployment")
        void testResilienceAgentKubernetesIntegration() {
                AgentRequest request = AgentRequest.builder()
                                .type("resilience-engineering")
                                .context(AgentContext.builder()
                                                .property("serviceName", "pos-customer")
                                                .property("failureType", "network")
                                                .property("platform", "kubernetes")
                                                .build())
                                .securityContext(securityContext)
                                .build();

                AgentResponse response = agentManager.processRequest(request);
                assertNotNull(response);
                assertTrue(response.isSuccess());
        }

        @Test
        @DisplayName("Multi-agent style service deployment requests succeed")
        void testMultiAgentMicroserviceDeployment() {
                String serviceName = "pos-vehicle-inventory";

                List<AgentRequest> requests = List.of(
                                AgentRequest.builder()
                                                .type("event-driven")
                                                .context(AgentContext.builder()
                                                                .property("serviceName", serviceName)
                                                                .property("eventType", "InventoryUpdated")
                                                                .build())
                                                .build(),
                                AgentRequest.builder()
                                                .type("cicd-pipeline")
                                                .context(AgentContext.builder()
                                                                .property("serviceName", serviceName)
                                                                .property("deploymentTarget", "AWS")
                                                                .build())
                                                .build(),
                                AgentRequest.builder()
                                                .type("configuration-management")
                                                .context(AgentContext.builder()
                                                                .property("serviceName", serviceName)
                                                                .property("configurationType", "secrets")
                                                                .build())
                                                .build(),
                                AgentRequest.builder()
                                                .type("resilience-engineering")
                                                .context(AgentContext.builder()
                                                                .property("serviceName", serviceName)
                                                                .property("failureType", "database")
                                                                .build())
                                                .build());

                requests.forEach(req -> {
                        AgentResponse response = agentManager.processRequest(req);
                        assertNotNull(response);
                        assertTrue(response.isSuccess());
                });
        }

        @Test
        @DisplayName("Service integration capabilities succeed across types")
        void testAgentRegistryServiceIntegration() {
                String[] types = { "event-driven", "cicd-pipeline", "configuration-management",
                                "resilience-engineering" };
                for (String type : types) {
                        AgentRequest request = AgentRequest.builder()
                                        .type(type)
                                        .context(AgentContext.builder().build())
                                        .securityContext(securityContext)
                                        .build();

                        AgentResponse response = agentManager.processRequest(request);
                        assertNotNull(response);
                        assertTrue(response.isSuccess(), "Integration should succeed for type " + type);
                }
        }

        @Test
        @DisplayName("Service-specific routing via request context works")
        void testServiceSpecificAgentRouting() {
                AgentContext ctx = AgentContext.builder()
                                .domain("business")
                                .property("serviceName", "pos-order")
                                .property("businessContext", "order-processing")
                                .property("technology", "spring-boot")
                             //   .securityContext(securityContext)
                                .build();

                // Event-driven
                AgentResponse eventResponse = agentManager.processRequest(AgentRequest.builder()
                                .type("event-driven")
                                .context(ctx)
                                .build());
                assertNotNull(eventResponse);
                assertTrue(eventResponse.isSuccess());

                // CI/CD
                AgentResponse cicdResponse = agentManager.processRequest(AgentRequest.builder()
                                .type("cicd-pipeline")
                                .context(ctx)
                                .build());
                assertNotNull(cicdResponse);
                assertTrue(cicdResponse.isSuccess());

                // Configuration
                AgentResponse configResponse = agentManager.processRequest(AgentRequest.builder()
                                .type("configuration-management")
                                .context(ctx)
                                .build());
                assertNotNull(configResponse);
                assertTrue(configResponse.isSuccess());

                // Resilience
                AgentResponse resilienceResponse = agentManager.processRequest(AgentRequest.builder()
                                .type("resilience-engineering")
                                .context(ctx)
                                .build());
                assertNotNull(resilienceResponse);
                assertTrue(resilienceResponse.isSuccess());
        }
}

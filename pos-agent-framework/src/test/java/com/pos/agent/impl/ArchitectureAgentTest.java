package com.pos.agent.impl;

import com.pos.agent.core.AgentManager;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.context.AgentContext;
import com.pos.agent.core.SecurityContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for architecture agent guidance using core API.
 * Tests DDD, microservices, integration patterns, and technology stack
 * decisions.
 */
class ArchitectureAgentTest {

        private final AgentManager agentManager = new AgentManager();
        private final SecurityContext securityContext = SecurityContext.builder()
                        .jwtToken("valid-jwt-token-for-tests")
                        .userId("architecture-agent-tester")
                        .roles(List.of("ARCHITECT", "TECHNICAL_LEAD"))
                        .permissions(List.of("AGENT_READ", "AGENT_WRITE", "architecture.design", "technology.select"))
                        .serviceId("pos-architecture-agent-tests")
                        .serviceType("test")
                        .build();

        @Test
        void testDomainDrivenDesignGuidance() {
                AgentContext context = AgentContext.builder()
                                .domain("architecture")
                                .property("topic", "ddd")
                                .property("query", "I need guidance on domain boundaries for the POS system")
                                .property("keywords", "ddd domain boundaries pos system")
                                .build();

                AgentRequest request = AgentRequest.builder()
                                .type("architecture")
                                .description("Guidance on domain boundaries for the POS system")
                                .context(context)
                                .securityContext(securityContext)
                                .build();

                AgentResponse response = agentManager.processRequest(request);

                assertTrue(response.isSuccess());
                assertNotNull(response.getStatus());
        }

        @Test
        void testMicroserviceBoundaryEnforcement() {
                AgentContext context = AgentContext.builder()
                                .domain("architecture")
                                .property("topic", "microservices")
                                .property("query", "How should I structure microservices for the POS system?")
                                .property("keywords", "microservice boundaries pos services")
                                .build();

                AgentRequest request = AgentRequest.builder()
                                .type("architecture")
                                .description("Guidance on microservice boundaries for the POS system")
                                .context(context)
                                .securityContext(securityContext)
                                .build();

                AgentResponse response = agentManager.processRequest(request);

                assertTrue(response.isSuccess());
                assertNotNull(response.getStatus());
        }

        @Test
        void testIntegrationPatternSpecification() {
                AgentContext context = AgentContext.builder()
                                .domain("architecture")
                                .property("topic", "integration-patterns")
                                .property("query", "What integration patterns should I use for the POS system?")
                                .property("keywords", "integration patterns api gateway messaging events")
                                .build();

                AgentRequest request = AgentRequest.builder()
                                .type("architecture")
                                .description("Guidance on integration patterns for the POS system")
                                .context(context)
                                .securityContext(securityContext)
                                .build();

                AgentResponse response = agentManager.processRequest(request);

                assertTrue(response.isSuccess());
                assertNotNull(response.getStatus());
        }

        @Test
        void testTechnologyStackValidation() {
                AgentContext context = AgentContext.builder()
                                .domain("architecture")
                                .property("topic", "technology-stack")
                                .property("query", "What technology stack should I use for Java development?")
                                .property("keywords", "technology stack java spring boot aws")
                                .build();

                AgentRequest request = AgentRequest.builder()
                                .type("architecture")
                                .description("Guidance on technology stack for Java development")
                                .context(context)
                                .securityContext(securityContext)
                                .build();

                AgentResponse response = agentManager.processRequest(request);

                assertTrue(response.isSuccess());
                assertNotNull(response.getStatus());
        }

        @Test
        void testCloudArchitectureGuidance() {
                AgentContext context = AgentContext.builder()
                                .domain("architecture")
                                .property("topic", "cloud-architecture")
                                .property("query", "How to design cloud-native architecture for POS?")
                                .property("keywords", "cloud native aws microservices")
                                .build();

                AgentRequest request = AgentRequest.builder()
                                .type("architecture")
                                .description("Guidance on cloud-native architecture for POS system")
                                .context(context)
                                .securityContext(securityContext)
                                .build();

                AgentResponse response = agentManager.processRequest(request);

                assertTrue(response.isSuccess());
                assertNotNull(response.getStatus());
        }
}
package com.pos.agent.framework.routing;

import com.pos.agent.core.AgentManager;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.context.AgentContext;
import com.pos.agent.context.ArchitectureContext;
import com.pos.agent.context.BusinessContext;
import com.pos.agent.context.CICDContext;
import com.pos.agent.context.ConfigurationContext;
import com.pos.agent.context.DefaultContext;
import com.pos.agent.context.DeploymentContext;
import com.pos.agent.context.EventDrivenContext;
import com.pos.agent.context.ImplementationContext;
import com.pos.agent.context.ResilienceContext;
import com.pos.agent.context.TestingContext;
import com.pos.agent.core.SecurityContext;
import net.jqwik.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for context-based agent selection and routing.
 * Tests agent selection based on technical context keywords and multi-domain
 * scenarios.
 */
class ContextBasedAgentSelectorTest {

        private final AgentManager agentManager = new AgentManager();
        private final SecurityContext securityContext = SecurityContext.builder()
                        .jwtToken("valid-jwt-token-for-tests")
                        .userId("routing-tester")
                        .roles(List.of("AGENT_ROUTER", "DOMAIN_SELECTOR"))
                        .permissions(List.of("AGENT_READ", "AGENT_WRITE", "agent.select", "agent.route",
                                        "context.analyze"))
                        .serviceId("pos-routing-tests")
                        .serviceType("property")
                        .build();

        @Property(tries = 100)
        void contextBasedAgentSelection(@ForAll("technicalContexts") AgentContext context) {
                AgentRequest request = AgentRequest.builder()
                                .type("context-selection")
                                .description("Technical context for agents")
                                .context(context)
                                .securityContext(securityContext)
                                .build();

                AgentResponse response = agentManager.processRequest(request);

                assertTrue(response.isSuccess());
                assertNotNull(response.getStatus());
        }

        @Property(tries = 100)
        void multiDomainContextHandling(@ForAll("multiDomainContexts") AgentContext context) {
                AgentRequest request = AgentRequest.builder()
                                .type("multi-domain-selection")
                                .description("Multi Domain Context Selection Test")
                                .context(context)
                                .securityContext(securityContext)
                                .build();

                AgentResponse response = agentManager.processRequest(request);

                assertTrue(response.isSuccess());
                assertNotNull(response.getStatus());
        }

        @Property(tries = 100)
        void edgeCaseContextHandling(@ForAll("edgeCaseContexts") AgentContext context) {
                AgentRequest request = AgentRequest.builder()
                                .type("edge-case-selection")
                                .description("Edge Case Context Selection Test")
                                .context(context)
                                .securityContext(securityContext)
                                .build();

                AgentResponse response = agentManager.processRequest(request);

                assertTrue(response.isSuccess());
                assertNotNull(response.getStatus());
        }

        @Property(tries = 100)
        void complexityAnalysis(@ForAll("complexityContexts") AgentContext context) {
                AgentRequest request = AgentRequest.builder()
                                .type("complexity-analysis")
                                .description("Complexity Analysis Context Selection Test")
                                .context(context)
                                .securityContext(securityContext)
                                .build();

                AgentResponse response = agentManager.processRequest(request);

                assertTrue(response.isSuccess());
                assertNotNull(response.getStatus());
        }

        @Provide
        Arbitrary<AgentContext> technicalContexts() {
                return Arbitraries.of(
                                ImplementationContext.builder()
                                                .property("technicalContext", "Spring Boot microservice")
                                                .build(),
                               com.pos.agent.context.SecurityContext.builder()
                                                .property("technicalContext", "JWT authentication")
                                                .build(),
                                ArchitectureContext.builder()
                                                .property("technicalContext", "Microservices design")
                                                .build(),
                                TestingContext.builder()
                                                .property("technicalContext", "JUnit integration tests")
                                                .build(),
                                DeploymentContext.builder()
                                                .property("technicalContext", "Docker containerization")
                                                .build(),
                                EventDrivenContext.builder()
                                                .property("technicalContext", "Kafka streaming")
                                                .build(),
                                CICDContext.builder()
                                                .property("technicalContext", "Jenkins pipeline")
                                                .build(),
                                ConfigurationContext.builder()
                                                .property("technicalContext", "Spring Cloud Config")
                                                .build(),
                                ResilienceContext.builder()
                                                .property("technicalContext", "Circuit breaker patterns")
                                                .build(),
                                BusinessContext.builder()
                                                .property("technicalContext", "POS inventory management")
                                                .build());
        }

        @Provide
        Arbitrary<AgentContext> multiDomainContexts() {
                return Arbitraries.of(
                                "Spring Boot with Docker and security",
                                "Microservices with Kafka and testing",
                                "CI/CD with deployment and resilience").map(
                                                description -> DefaultContext.builder()
                                                                .domain("multi-domain")
                                                                .property("description", description)
                                                                .property("isComplex", true)
                                                                .build());
        }

        @Provide
        Arbitrary<AgentContext> edgeCaseContexts() {
                return Arbitraries.of(
                                "empty:",
                                "unrecognized:Random unrelated content",
                                "minimal:X").map(contextStr -> {
                                        String[] parts = contextStr.split(":", 2);
                                        return DefaultContext.builder()
                                                        .domain(parts[0])
                                                        .property("context", parts.length > 1 ? parts[1] : "")
                                                        .property("isEdgeCase", true)
                                                        .build();
                                });
        }

        @Provide
        Arbitrary<AgentContext> complexityContexts() {
                return Arbitraries.of(
                                "simple:Spring Boot REST API",
                                "complex:Spring Boot with Docker, JWT, Kafka, and circuit breakers").map(contextStr -> {
                                        String[] parts = contextStr.split(":", 2);
                                        boolean isComplex = "complex".equals(parts[0]);
                                        return DefaultContext.builder()
                                                        .domain("complexity-analysis")
                                                        .property("description", parts[1])
                                                        .property("isComplex", isComplex)
                                                        .build();
                                });
        }
}

package com.pos.agent.framework.routing;

import com.pos.agent.core.AgentManager;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.context.AgentContext;
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
            .permissions(List.of("agent.select", "agent.route", "context.analyze"))
            .serviceId("pos-routing-tests")
            .serviceType("property")
            .build();

    @Property(tries = 100)
    void contextBasedAgentSelection(@ForAll("technicalContexts") AgentContext context) {
        AgentRequest request = AgentRequest.builder()
                .type("context-selection")
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
                "implementation:Spring Boot microservice",
                "security:JWT authentication",
                "architecture:Microservices design",
                "testing:JUnit integration tests",
                "deployment:Docker containerization",
                "event-driven:Kafka streaming",
                "cicd:Jenkins pipeline",
                "configuration:Spring Cloud Config",
                "resilience:Circuit breaker patterns",
                "business:POS inventory management").map(contextStr -> {
                    String[] parts = contextStr.split(":");
                    return AgentContext.builder()
                            .domain(parts[0])
                            .property("technicalContext", parts[1])
                            .build();
                });
    }

    @Provide
    Arbitrary<AgentContext> multiDomainContexts() {
        return Arbitraries.of(
                "Spring Boot with Docker and security",
                "Microservices with Kafka and testing",
                "CI/CD with deployment and resilience").map(
                        description -> AgentContext.builder()
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
                    return AgentContext.builder()
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
                    return AgentContext.builder()
                            .domain("complexity-analysis")
                            .property("description", parts[1])
                            .property("isComplex", isComplex)
                            .build();
                });
    }
}

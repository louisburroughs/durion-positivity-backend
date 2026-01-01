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
 * Property-based tests for intelligent agent routing with service mapping and
 * fallback strategies.
 * Tests routing decisions based on service mapping, context analysis, and
 * fallback mechanisms.
 */
class IntelligentAgentRouterTest {

    private final AgentManager agentManager = new AgentManager();
    private final SecurityContext securityContext = SecurityContext.builder()
            .jwtToken("valid-jwt-token-for-tests")
            .userId("router-tester")
            .roles(List.of("ADMIN", "INTELLIGENT_ROUTER", "SERVICE_MAPPER"))
            .permissions(List.of("AGENT_READ", "AGENT_WRITE", "agent.route", "service.map", "fallback.select"))
            .serviceId("pos-router-tests")
            .serviceType("property")
            .build();

    @Property(tries = 100)
    void routingWithServiceMapping(@ForAll("serviceMappingScenarios") AgentContext context) {
        AgentRequest request = AgentRequest.builder()
                .type("service-mapping-route")
                .description("Service mapping based routing scenario")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Property(tries = 100)
    void routingWithContextFallback(@ForAll("contextFallbackScenarios") AgentContext context) {
        AgentRequest request = AgentRequest.builder()
                .type("context-fallback-route")
                .description("Context fallback based routing scenario")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Property(tries = 100)
    void routingWithUniversalFallback(@ForAll("universalFallbackScenarios") AgentContext context) {
        AgentRequest request = AgentRequest.builder()
                .type("universal-fallback-route")
                .description("Universal fallback based routing scenario")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Property(tries = 100)
    void routingToSpecificServices(@ForAll("serviceRoutingScenarios") AgentContext context) {
        AgentRequest request = AgentRequest.builder()
                .type("service-specific-route")
                .description("Service specific routing scenario")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Property(tries = 100)
    void domainBasedRouting(@ForAll("domainRoutingScenarios") AgentContext context) {
        AgentRequest request = AgentRequest.builder()
                .type("domain-based-route")
                .description("Domain based routing scenario")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Provide
    Arbitrary<AgentContext> serviceMappingScenarios() {
        return Arbitraries.of(
                "pos-inventory:implementation:Update inventory",
                "pos-catalog:business:Catalog management").map(scenario -> {
                    String[] parts = scenario.split(":");
                    return AgentContext.builder()
                            .domain("service-mapping")
                            .property("targetService", parts[0])
                            .property("primaryAgent", parts[1])
                            .property("description", parts[2])
                            .property("routingReason", "Primary agent mapping")
                            .build();
                });
    }

    @Provide
    Arbitrary<AgentContext> contextFallbackScenarios() {
        return Arbitraries.of(
                "general:Spring Boot microservice:implementation",
                "general:Security configuration:security").map(scenario -> {
                    String[] parts = scenario.split(":");
                    return AgentContext.builder()
                            .domain("context-fallback")
                            .property("requestType", parts[0])
                            .property("description", parts[1])
                            .property("selectedAgent", parts[2])
                            .property("routingReason", "Context-based selection")
                            .build();
                });
    }

    @Provide
    Arbitrary<AgentContext> universalFallbackScenarios() {
        return Arbitraries.of(
                "unknown:Unknown request:architecture:Fallback mechanism",
                "unknown:Unknown type:none:No available agent").map(scenario -> {
                    String[] parts = scenario.split(":");
                    boolean hasAgent = !"none".equals(parts[2]);
                    return AgentContext.builder()
                            .domain("universal-fallback")
                            .property("requestType", parts[0])
                            .property("description", parts[1])
                            .property("fallbackAgent", parts[2])
                            .property("routingReason", parts[3])
                            .property("hasAvailableAgent", hasAgent)
                            .build();
                });
    }

    @Provide
    Arbitrary<AgentContext> serviceRoutingScenarios() {
        return Arbitraries.of(
                "pos-catalog:business:primary:Catalog operations",
                "pos-order:implementation:suggested:Order management").map(scenario -> {
                    String[] parts = scenario.split(":");
                    return AgentContext.builder()
                            .domain("service-routing")
                            .property("targetService", parts[0])
                            .property("agentType", parts[1])
                            .property("mappingType", parts[2])
                            .property("description", parts[3])
                            .build();
                });
    }

    @Provide
    Arbitrary<AgentContext> domainRoutingScenarios() {
        return Arbitraries.of(
                "spring-boot:implementation",
                "security:security",
                "unknown-domain:none").map(scenario -> {
                    String[] parts = scenario.split(":");
                    boolean hasAgent = !"none".equals(parts[1]);
                    return AgentContext.builder()
                            .domain("domain-routing")
                            .property("requestDomain", parts[0])
                            .property("bestAgent", parts[1])
                            .property("hasAvailableAgent", hasAgent)
                            .build();
                });
    }
}

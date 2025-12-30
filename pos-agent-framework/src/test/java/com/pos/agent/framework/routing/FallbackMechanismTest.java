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
 * Property-based tests for fallback mechanism in agent routing.
 * Tests fallback strategies for unavailable agents and routing failures.
 */
class FallbackMechanismTest {

    private final AgentManager agentManager = new AgentManager();
    private final SecurityContext securityContext = SecurityContext.builder()
            .jwtToken("valid-jwt-token-for-tests")
            .userId("fallback-tester")
            .roles(List.of("FALLBACK_HANDLER", "AGENT_ROUTER"))
            .permissions(List.of("agent.fallback", "agent.route", "agent.recover"))
            .serviceId("pos-fallback-tests")
            .serviceType("property")
            .build();

    @Property(tries = 100)
    void contextBasedFallback(@ForAll("contextFallbackScenarios") AgentContext context) {
        AgentRequest request = AgentRequest.builder()
                .type("context-fallback")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Property(tries = 100)
    void domainBasedFallback(@ForAll("domainFallbackScenarios") AgentContext context) {
        AgentRequest request = AgentRequest.builder()
                .type("domain-fallback")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Property(tries = 100)
    void universalFallback(@ForAll("universalFallbackScenarios") AgentContext context) {
        AgentRequest request = AgentRequest.builder()
                .type("universal-fallback")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Property(tries = 100)
    void agentFailureFallback(@ForAll("agentFailureScenarios") AgentContext context) {
        AgentRequest request = AgentRequest.builder()
                .type("agent-failure-fallback")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Provide
    Arbitrary<AgentContext> contextFallbackScenarios() {
        return Arbitraries.of(
                "implementation:Spring Boot implementation",
                "unknown:Unknown context").map(scenario -> {
                    String[] parts = scenario.split(":", 2);
                    return AgentContext.builder()
                            .domain("context-fallback")
                            .property("requestType", parts[0])
                            .property("description", parts[1])
                            .property("requiresFallback", "unknown".equals(parts[0]))
                            .build();
                });
    }

    @Provide
    Arbitrary<AgentContext> domainFallbackScenarios() {
        return Arbitraries.of(
                "spring-boot",
                "security",
                "unknown-domain").map(
                        domain -> AgentContext.builder()
                                .domain("domain-fallback")
                                .property("requestDomain", domain)
                                .property("requiresFallback", "unknown-domain".equals(domain))
                                .build());
    }

    @Provide
    Arbitrary<AgentContext> universalFallbackScenarios() {
        return Arbitraries.of(
                "architecture:available",
                "implementation:available",
                "documentation:available",
                "none:unavailable").map(scenario -> {
                    String[] parts = scenario.split(":");
                    return AgentContext.builder()
                            .domain("universal-fallback")
                            .property("fallbackAgent", parts[0])
                            .property("agentAvailable", "available".equals(parts[1]))
                            .build();
                });
    }

    @Provide
    Arbitrary<AgentContext> agentFailureScenarios() {
        return Arbitraries.of(
                "implementation:architecture",
                "security:implementation",
                "deployment:architecture",
                "testing:implementation",
                "event-driven:architecture",
                "cicd:deployment",
                "configuration:implementation",
                "resilience:architecture",
                "unknown:none").map(scenario -> {
                    String[] parts = scenario.split(":");
                    return AgentContext.builder()
                            .domain("agent-failure-fallback")
                            .property("failedAgent", parts[0])
                            .property("fallbackAgent", parts[1])
                            .property("hasFallback", !"none".equals(parts[1]))
                            .build();
                });
    }
}

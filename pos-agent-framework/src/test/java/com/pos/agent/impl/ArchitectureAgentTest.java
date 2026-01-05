package com.pos.agent.impl;

import com.pos.agent.context.AgentContext;
import com.pos.agent.context.ArchitectureContext;
import com.pos.agent.core.AgentProcessingState;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.core.Permissions;
import com.pos.agent.core.Roles;
import com.pos.agent.core.SecurityContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ArchitectureAgentTest {

    private static String originalSecret;

    private ArchitectureAgent agent;

    @BeforeAll
    static void setUpSecret() {
        originalSecret = System.getProperty("AGENT_JWT_SECRET");
        System.setProperty("AGENT_JWT_SECRET", "test-secret");
    }

    @AfterAll
    static void restoreSecret() {
        if (originalSecret == null) {
            System.clearProperty("AGENT_JWT_SECRET");
        } else {
            System.setProperty("AGENT_JWT_SECRET", originalSecret);
        }
    }

    @BeforeEach
    void setUp() {
        agent = new ArchitectureAgent();
    }

    @Test
    void getOrCreateContext_CachesPerSession() {
        AgentContext first = agent.getOrCreateContext("session-1");
        AgentContext second = agent.getOrCreateContext("session-1");

        assertThat(first)
                .isInstanceOf(ArchitectureContext.class)
                .isSameAs(second);
    }

    @Test
    void processRequest_PopulatesAnalysisAndRecommendations() {
        ArchitectureContext context = ArchitectureContext.builder()
                .description("Payments platform")
                .property("systemType", "microservices")
                .property("currentPatterns", List.of("CQRS"))
                .property("requirements", List.of("performance", "security"))
                .property("constraints", Map.of("budget", "limited"))
                .property("targetScale", "large")
                .build();

        SecurityContext securityContext = SecurityContext.builder()
                .userId("tester")
                .roles(List.of(Roles.ARCHITECT))
                .permissions(List.of(Permissions.AGENT_READ))
                .serviceId("svc-1")
                .serviceType("internal")
                .build();

        AgentRequest request = AgentRequest.builder()
                .agentContext(context)
                .securityContext(securityContext)
                .priority(AgentRequest.Priority.NORMAL)
                .build();

        AgentResponse response = agent.processRequest(request);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getStatus()).isEqualTo(AgentProcessingState.SUCCESS);
        assertThat(response.getRecommendations()).isNotEmpty();
        assertThat(response.getOutput()).contains("Architecture Analysis for");

        assertThat(context.getProperties()).containsKeys(
                "patterns_evaluated",
                "trade_offs",
                "system_type",
                "target_scale");

        assertThat((List<?>) context.getProperties().get("patterns_evaluated"))
                .isNotEmpty();
    }

    @Test
    void removeContext_ClearsAllAssociatedContexts() {
        String sessionId = "session-remove";

        AgentContext architecture = agent.getOrCreateContext(sessionId);
        AgentContext eventDriven = agent.getOrCreateEventDrivenContext(sessionId);
        AgentContext cicd = agent.getOrCreateCICDContext(sessionId);
        AgentContext configuration = agent.getOrCreateConfigurationContext(sessionId);
        AgentContext resilience = agent.getOrCreateResilienceContext(sessionId);

        agent.removeContext(sessionId);

        assertThat(agent.getOrCreateContext(sessionId)).isNotSameAs(architecture);
        assertThat(agent.getOrCreateEventDrivenContext(sessionId)).isNotSameAs(eventDriven);
        assertThat(agent.getOrCreateCICDContext(sessionId)).isNotSameAs(cicd);
        assertThat(agent.getOrCreateConfigurationContext(sessionId)).isNotSameAs(configuration);
        assertThat(agent.getOrCreateResilienceContext(sessionId)).isNotSameAs(resilience);
    }

    @Test
    void processRequest_ReturnsFailureWhenRequestIsNull() {
        AgentResponse response = agent.processRequest(null);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getStatus()).isEqualTo(AgentProcessingState.FAILURE);
        assertThat(response.getErrorMessage()).contains("request is null");
    }
}

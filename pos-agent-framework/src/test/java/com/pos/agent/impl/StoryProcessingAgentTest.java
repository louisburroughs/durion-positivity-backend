package com.pos.agent.impl;

import com.pos.agent.context.DefaultContext;
import com.pos.agent.context.StoryContext;
import com.pos.agent.core.AgentProcessingState;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.core.SecurityContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StoryProcessingAgentTest {

    private static final String TEST_SECRET = "test-secret";
    private static String originalSecret;

    private StoryProcessingAgent agent;

    @BeforeAll
    static void configureJwtSecret() {
        originalSecret = System.getProperty("agent.jwt.secret");
        System.setProperty("agent.jwt.secret", TEST_SECRET);
    }

    @AfterAll
    static void restoreJwtSecret() {
        if (originalSecret == null) {
            System.clearProperty("agent.jwt.secret");
        } else {
            System.setProperty("agent.jwt.secret", originalSecret);
        }
    }

    @BeforeEach
    void setUp() {
        agent = new StoryProcessingAgent();
        StoryProcessingAgent.CONTEXT_MAP.clear();
    }

    @Test
    void getOrCreateContextCachesPerSession() {
        var ctx1 = agent.getOrCreateContext("session-1");
        var ctx2 = agent.getOrCreateContext("session-1");

        assertThat(ctx1).isSameAs(ctx2);
        assertThat(ctx1.getSessionId()).isEqualTo(ctx2.getSessionId());
    }

    @Test
    void removeContextEvictsCachedContext() {
        var original = agent.getOrCreateContext("session-2");

        agent.removeContext("session-2");
        var recreated = agent.getOrCreateContext("session-2");

        assertThat(recreated).isNotSameAs(original);
    }

    @Test
    void processRequestWithInvalidContextTypeReturnsFailure() {
        AgentRequest request = AgentRequest.builder()
                .agentContext(DefaultContext.builder().description("not-story-context").build())
                .securityContext(SecurityContext.builder().userId("tester").build())
                .build();

        AgentResponse response = agent.processRequest(request);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getStatus()).isEqualTo(AgentProcessingState.FAILURE);
        assertThat(response.getOutput()).contains("Invalid context type");
    }

    @Test
    void processRequestWithInvalidModuleReturnsFailure() {
        StoryContext context = StoryContext.builder()
                .issueId(10L)
                .issueTitle("Invalid module story")
                .issueBody("AC: ensure failure")
                .moduleName("unknown-module")
                .dependencies(List.of())
                .build();

        AgentResponse response = agent.processRequest(buildRequest(context));

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getStatus()).isEqualTo(AgentProcessingState.FAILURE);
        assertThat(response.getOutput()).contains("Module not found: unknown-module");
    }

    @Test
    void processRequestDetectsCircularDependency() {
        StoryContext context = StoryContext.builder()
                .issueId(42L)
                .issueTitle("Circular dependency story")
                .issueBody("Modules: pos-order")
                .moduleName("pos-order")
                .dependencies(List.of("42"))
                .build();

        AgentResponse response = agent.processRequest(buildRequest(context));

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getStatus()).isEqualTo(AgentProcessingState.FAILURE);
        assertThat(response.getOutput()).contains("Detected circular dependency in story: 42");
    }

    @Test
    void processRequestBuildsOutputWithModulesAndAcceptanceCriteria() {
        String issueBody = "Modules: pos-inventory, pos-catalog\nAC: user can add items\nAC: system persists orders";
        StoryContext context = StoryContext.builder()
                .issueId(77L)
                .issueTitle("Checkout flow story")
                .issueBody(issueBody)
                .moduleName("pos-order")
                .dependencies(List.of())
                .build();

        AgentResponse response = agent.processRequest(buildRequest(context));

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getStatus()).isEqualTo(AgentProcessingState.SUCCESS);
        assertThat(response.getOutput()).contains("## Build Results");
        assertThat(response.getOutput())
                .contains("- pos-order")
                .contains("- pos-inventory")
                .contains("- pos-catalog")
                .contains("- user can add items")
                .contains("- system persists orders");
        assertThat(response.getRecommendations()).isNotEmpty();
        assertThat(response.getConfidence()).isGreaterThan(0.5);
    }

    @Test
    void processRequestHandlesNullIssueBodyGracefully() {
        StoryContext context = StoryContext.builder()
                .issueId(88L)
                .issueTitle("Story without body")
                .issueBody(null)
                .moduleName("pos-accounting")
                .dependencies(List.of())
                .build();

        AgentResponse response = agent.processRequest(buildRequest(context));

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getStatus()).isEqualTo(AgentProcessingState.SUCCESS);
        assertThat(response.getOutput()).contains("- pos-accounting");
    }

    private AgentRequest buildRequest(StoryContext context) {
        return AgentRequest.builder()
                .agentContext(context)
                .securityContext(SecurityContext.builder()
                        .userId("tester")
                        .serviceId("service")
                        .serviceType("integration")
                        .build())
                .build();
    }
}

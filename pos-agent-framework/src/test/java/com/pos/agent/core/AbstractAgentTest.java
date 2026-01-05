package com.pos.agent.core;

import com.pos.agent.context.AgentContext;
import com.pos.agent.context.DefaultContext;
import com.pos.agent.framework.model.AgentType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class AbstractAgentTest {

    private static String originalSecret;

    private TestAgent agent;

    @BeforeAll
    static void setUpSecret() {
        originalSecret = System.getProperty("agent.jwt.secret");
        System.setProperty("agent.jwt.secret", "test-secret");
    }

    @AfterAll
    static void restoreSecret() {
        if (originalSecret == null) {
            System.clearProperty("agent.jwt.secret");
        } else {
            System.setProperty("agent.jwt.secret", originalSecret);
        }
    }

    @BeforeEach
    void setUp() {
        // Ensure JWT secret is available for this test
        if (System.getProperty("agent.jwt.secret") == null) {
            System.setProperty("agent.jwt.secret", "test-secret");
        }
        agent = new TestAgent();
    }

    @AfterEach
    void tearDown() {
        agent.removeContext("session-1");
        agent.removeContext("session-2");
        agent.removeContext("session-3");
    }

    @Test
    void getOrCreateContext_CachesPerSession() {
        AgentContext first = agent.getOrCreateContext("session-1");
        AgentContext second = agent.getOrCreateContext("session-1");

        assertThat(first)
                .isInstanceOf(DefaultContext.class)
                .isSameAs(second);
    }

    @Test
    void updateContext_StoresGuidance() {
        agent.updateContext("session-2", "do things");
        DefaultContext context = (DefaultContext) agent.getOrCreateContext("session-2");

        assertThat(context.getProperties()).containsEntry("guidance", "do things");
    }

    @Test
    void processRequest_ValidRequestDelegatesToImplementation() {
        AgentRequest request = buildValidRequest("session-3");

        AgentResponse response = agent.processRequest(request);

        assertThat(agent.lastProcessedRequest).isSameAs(request);
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getStatus()).isEqualTo(AgentProcessingState.SUCCESS);
        assertThat(response.getOutput()).isEqualTo("ok");
    }

    @Test
    void processRequest_NullRequestReturnsFailure() {
        AgentResponse response = agent.processRequest(null);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getStatus()).isEqualTo(AgentProcessingState.FAILURE);
        assertThat(response.getErrorMessage()).contains("request is null");
    }

    @Test
    void getCapabilities_IsUnmodifiable() {
        assertThat(agent.getCapabilities()).containsExactly("cap-1", "cap-2");
        assertThatThrownBy(() -> agent.getCapabilities().add("extra"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void isHealthyReflectsStatus() {
        assertThat(agent.isHealthy()).isTrue();

        agent.setStatus(AgentStatus.UNHEALTHY);

        assertThat(agent.isHealthy()).isFalse();
        assertThat(agent.getStatus()).isEqualTo(AgentStatus.UNHEALTHY);
    }

    @Test
    void generateOutputIncludesDomainAndQuery() {
        String output = agent.generateOutput("data pipeline");

        assertThat(output).contains("data pipeline");
        assertThat(output).contains("architecture");
    }

    private AgentRequest buildValidRequest(String sessionId) {
        AgentContext context = DefaultContext.builder()
                .sessionId(sessionId)
                .requestId(sessionId)
                .build();
        SecurityContext securityContext = buildSecurityContext();

        return AgentRequest.builder()
                .agentContext(context)
                .securityContext(securityContext)
                .priority(AgentRequest.Priority.NORMAL)
                .build();
    }

    private SecurityContext buildSecurityContext() {
        return SecurityContext.builder()
                .userId("user-1")
                .roles(List.of(Roles.USER))
                .permissions(List.of(Permissions.AGENT_READ))
                .serviceId("service-1")
                .serviceType("internal")
                .build();
    }

    private static class TestAgent extends AbstractAgent {
        private AgentRequest lastProcessedRequest;

        TestAgent() {
            super(AgentType.ARCHITECTURE, List.of("cap-1", "cap-2"));
        }

        @Override
        protected AgentResponse doProcessRequest(AgentRequest request) {
            lastProcessedRequest = request;
            return AgentResponse.builder()
                    .status(AgentProcessingState.SUCCESS)
                    .output("ok")
                    .confidence(0.9)
                    .success(true)
                    .agentContext(request.getAgentContext())
                    .recommendations(List.of())
                    .build();
        }
    }
}

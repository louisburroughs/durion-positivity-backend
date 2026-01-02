package com.pos.agent.core;

import com.pos.agent.context.AgentContext;
import com.pos.agent.context.DefaultContext;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AbstractAgent base class
 * Tests validation logic and failure response creation
 */
class AbstractAgentTest {

    /**
     * Test implementation of AbstractAgent for testing purposes
     */
    private static class TestAgent extends AbstractAgent {
        @Override
        protected AgentResponse doProcessRequest(AgentRequest request) {
            // Validate type - only "test" is valid
            if (!"test".equals(request.getType())) {
                return createFailureResponse("Invalid request: invalid type");
            }

            return AgentResponse.builder()
                    .status(AgentStatus.SUCCESS)
                    .output("Test output: " + request.getDescription())
                    .confidence(0.9)
                    .success(true)
                    .recommendations(List.of("test recommendation"))
                    .build();
        }
    }

    @Test
    void shouldRejectNullRequest() {
        TestAgent agent = new TestAgent();

        AgentResponse response = agent.processRequest(null);

        assertEquals(AgentStatus.FAILURE, response.getStatusEnum());
        assertFalse(response.isSuccess());
        assertEquals("Invalid request: request is null", response.getOutput());
        assertEquals(0.0, response.getConfidence());
        assertNotNull(response.getRecommendations());
        assertTrue(response.getRecommendations().isEmpty());
    }

    @Test
    void shouldRejectRequestWithNullDescription() {
        TestAgent agent = new TestAgent();
        AgentContext context = DefaultContext.builder()
                .domain("test")
                .build();

        // AgentRequest builder throws IllegalStateException for null description
        assertThrows(IllegalStateException.class, () -> {
            AgentRequest.builder()
                    .type("test")
                    .context(context)
                    .description(null)
                    .build();
        }, "Builder should reject null description");
    }

    @Test
    void shouldRejectRequestWithEmptyDescription() {
        TestAgent agent = new TestAgent();
        AgentContext context = DefaultContext.builder()
                .build();

        // AgentRequest builder throws IllegalStateException for whitespace-only
        // description
        assertThrows(IllegalStateException.class, () -> {
            AgentRequest.builder()
                    .type("test")
                    .context(context)
                    .description("   ")
                    .build();
        }, "Builder should reject whitespace-only description");
    }

    @Test
    void shouldRejectRequestWithNullContext() {
        TestAgent agent = new TestAgent();

        // AgentRequest builder throws IllegalStateException for null context
        assertThrows(IllegalStateException.class, () -> {
            AgentRequest.builder()
                    .type("test")
                    .description("test description")
                    .context(null)
                    .build();
        }, "Builder should reject null context");
    }

    @Test
    void shouldRejectRequestWithInvalidType() {
        TestAgent agent = new TestAgent();
        AgentContext context = DefaultContext.builder()
                .domain("test")
                .build();

        AgentRequest request = AgentRequest.builder()
                .type("invalid-type")
                .description("test description")
                .agentContext(context)
                .context("test context")
                .build();

        AgentResponse response = agent.processRequest(request);

        assertEquals(AgentStatus.FAILURE, response.getStatusEnum());
        assertFalse(response.isSuccess());
        assertEquals("Invalid request: invalid type", response.getOutput());
    }

    @Test
    void shouldProcessValidRequest() {
        TestAgent agent = new TestAgent();
        AgentContext context = DefaultContext.builder()
                .domain("test")
                .property("key", "value")
                .build();

        AgentRequest request = AgentRequest.builder()
                .type("test")
                .description("test description")
                .agentContext(context)
                .context("test context")
                .build();

        AgentResponse response = agent.processRequest(request);

        assertEquals(AgentStatus.SUCCESS, response.getStatusEnum());
        assertTrue(response.isSuccess());
        assertEquals("Test output: test description", response.getOutput());
        assertEquals(0.9, response.getConfidence());
        assertNotNull(response.getRecommendations());
        assertEquals(1, response.getRecommendations().size());
        assertEquals("test recommendation", response.getRecommendations().get(0));
    }

    @Test
    void shouldCreateProperFailureResponse() {
        TestAgent agent = new TestAgent();
        String errorMessage = "Test error message";

        AgentResponse response = agent.createFailureResponse(errorMessage);

        assertEquals(AgentStatus.FAILURE, response.getStatusEnum());
        assertFalse(response.isSuccess());
        assertEquals(errorMessage, response.getOutput());
        assertEquals(errorMessage, response.getErrorMessage());
        assertEquals(0.0, response.getConfidence());
        assertNotNull(response.getRecommendations());
        assertTrue(response.getRecommendations().isEmpty());
    }
}

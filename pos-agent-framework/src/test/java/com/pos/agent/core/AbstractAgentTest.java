package com.pos.agent.core;

import com.pos.agent.context.AgentContext;
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
        AgentContext context = AgentContext.builder()
                .domain("test")
                .build();
        
        AgentRequest request = new AgentRequest();
        request.setType("test");
        request.setContext(context);
        request.setDescription(null);
        
        AgentResponse response = agent.processRequest(request);
        
        assertEquals(AgentStatus.FAILURE, response.getStatusEnum());
        assertFalse(response.isSuccess());
        assertEquals("Invalid request: description is required", response.getOutput());
        assertEquals(0.0, response.getConfidence());
    }

    @Test
    void shouldRejectRequestWithEmptyDescription() {
        TestAgent agent = new TestAgent();
        AgentContext context = AgentContext.builder()
                .domain("test")
                .build();
        
        AgentRequest request = new AgentRequest();
        request.setType("test");
        request.setContext(context);
        request.setDescription("   ");
        
        AgentResponse response = agent.processRequest(request);
        
        assertEquals(AgentStatus.FAILURE, response.getStatusEnum());
        assertFalse(response.isSuccess());
        assertEquals("Invalid request: description is required", response.getOutput());
    }

    @Test
    void shouldRejectRequestWithNullContext() {
        TestAgent agent = new TestAgent();
        
        AgentRequest request = new AgentRequest();
        request.setType("test");
        request.setDescription("test description");
        request.setContext(null);
        
        AgentResponse response = agent.processRequest(request);
        
        assertEquals(AgentStatus.FAILURE, response.getStatusEnum());
        assertFalse(response.isSuccess());
        assertEquals("Invalid request: context is required", response.getOutput());
    }

    @Test
    void shouldRejectRequestWithInvalidType() {
        TestAgent agent = new TestAgent();
        AgentContext context = AgentContext.builder()
                .domain("test")
                .build();
        
        AgentRequest request = new AgentRequest();
        request.setType("invalid-type");
        request.setDescription("test description");
        request.setContext(context);
        
        AgentResponse response = agent.processRequest(request);
        
        assertEquals(AgentStatus.FAILURE, response.getStatusEnum());
        assertFalse(response.isSuccess());
        assertEquals("Invalid request: invalid type", response.getOutput());
    }

    @Test
    void shouldProcessValidRequest() {
        TestAgent agent = new TestAgent();
        AgentContext context = AgentContext.builder()
                .domain("test")
                .property("key", "value")
                .build();
        
        AgentRequest request = new AgentRequest();
        request.setType("test");
        request.setDescription("test description");
        request.setContext(context);
        
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


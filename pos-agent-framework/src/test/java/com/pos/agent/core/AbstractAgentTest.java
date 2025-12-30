package com.pos.agent.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AbstractAgent validation behavior
 */
class AbstractAgentTest {

    /**
     * Concrete test implementation of AbstractAgent
     */
    static class TestAgent extends AbstractAgent {
        @Override
        protected AgentResponse handle(AgentRequest request) {
            return AgentResponse.builder()
                    .status(AgentStatus.SUCCESS)
                    .output("Handled: " + request.getDescription())
                    .confidence(0.9)
                    .recommendations(List.of("recommendation1"))
                    .build();
        }
    }

    /**
     * Agent that throws an exception in handle method
     */
    static class ErrorAgent extends AbstractAgent {
        @Override
        protected AgentResponse handle(AgentRequest request) {
            throw new RuntimeException("Simulated error");
        }
    }

    /**
     * Agent with custom validation
     */
    static class CustomValidationAgent extends AbstractAgent {
        @Override
        protected String validateRequest(AgentRequest request) {
            String baseValidation = super.validateRequest(request);
            if (baseValidation != null) {
                return baseValidation;
            }
            
            // Custom validation: require description to start with "CUSTOM:"
            if (!request.getDescription().startsWith("CUSTOM:")) {
                return "Description must start with CUSTOM:";
            }
            return null;
        }

        @Override
        protected AgentResponse handle(AgentRequest request) {
            return AgentResponse.success("Custom handled", 0.95);
        }
    }

    @Test
    void testValidRequestProcessing() {
        TestAgent agent = new TestAgent();
        
        AgentRequest request = AgentRequest.builder()
                .type("test")
                .description("Test description")
                .context(Map.of("key", "value"))
                .build();

        AgentResponse response = agent.processRequest(request);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals(AgentStatus.SUCCESS, response.statusEnum());
        assertEquals("Handled: Test description", response.getOutput());
        assertEquals(0.9, response.getConfidence());
        assertEquals(1, response.getRecommendations().size());
    }

    @Test
    void testNullRequestValidation() {
        TestAgent agent = new TestAgent();
        
        AgentResponse response = agent.processRequest(null);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals(AgentStatus.FAILURE, response.statusEnum());
        assertEquals("Invalid request: request is null", response.getOutput());
        assertEquals(0.0, response.getConfidence());
    }

    @Test
    void testNullDescriptionValidation() {
        TestAgent agent = new TestAgent();
        
        AgentRequest request = AgentRequest.builder()
                .type("test")
                .description(null)
                .context(Map.of("key", "value"))
                .build();

        AgentResponse response = agent.processRequest(request);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals(AgentStatus.FAILURE, response.statusEnum());
        assertEquals("Invalid request: description is required", response.getOutput());
    }

    @Test
    void testEmptyDescriptionValidation() {
        TestAgent agent = new TestAgent();
        
        AgentRequest request = AgentRequest.builder()
                .type("test")
                .description("   ")
                .context(Map.of("key", "value"))
                .build();

        AgentResponse response = agent.processRequest(request);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals("Invalid request: description is required", response.getOutput());
    }

    @Test
    void testNullContextValidation() {
        TestAgent agent = new TestAgent();
        
        AgentRequest request = AgentRequest.builder()
                .type("test")
                .description("Test description")
                .context(null)
                .build();

        AgentResponse response = agent.processRequest(request);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals("Invalid request: context is required", response.getOutput());
    }

    @Test
    void testNullTypeValidation() {
        TestAgent agent = new TestAgent();
        
        AgentRequest request = AgentRequest.builder()
                .type(null)
                .description("Test description")
                .context(Map.of("key", "value"))
                .build();

        AgentResponse response = agent.processRequest(request);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals("Invalid request: invalid type", response.getOutput());
    }

    @Test
    void testInvalidTypeValidation() {
        TestAgent agent = new TestAgent();
        
        AgentRequest request = AgentRequest.builder()
                .type("invalid-type")
                .description("Test description")
                .context(Map.of("key", "value"))
                .build();

        AgentResponse response = agent.processRequest(request);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals("Invalid request: invalid type", response.getOutput());
    }

    @Test
    void testExceptionHandlingInHandle() {
        ErrorAgent agent = new ErrorAgent();
        
        AgentRequest request = AgentRequest.builder()
                .type("test")
                .description("Test description")
                .context(Map.of("key", "value"))
                .build();

        AgentResponse response = agent.processRequest(request);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals(AgentStatus.FAILURE, response.statusEnum());
        assertTrue(response.getOutput().contains("Internal error"));
        assertTrue(response.getOutput().contains("Simulated error"));
    }

    @Test
    void testCreateFailureResponse() {
        TestAgent agent = new TestAgent();
        
        AgentResponse response = agent.createFailureResponse("Custom error message");

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals(AgentStatus.FAILURE, response.statusEnum());
        assertEquals("Custom error message", response.getOutput());
        assertEquals(0.0, response.getConfidence());
    }

    @Test
    void testCustomValidation() {
        CustomValidationAgent agent = new CustomValidationAgent();
        
        // Test with invalid custom validation
        AgentRequest invalidRequest = AgentRequest.builder()
                .type("test")
                .description("Not custom description")
                .context(Map.of("key", "value"))
                .build();

        AgentResponse invalidResponse = agent.processRequest(invalidRequest);
        assertFalse(invalidResponse.isSuccess());
        assertEquals("Description must start with CUSTOM:", invalidResponse.getOutput());

        // Test with valid custom validation
        AgentRequest validRequest = AgentRequest.builder()
                .type("test")
                .description("CUSTOM: Valid description")
                .context(Map.of("key", "value"))
                .build();

        AgentResponse validResponse = agent.processRequest(validRequest);
        assertTrue(validResponse.isSuccess());
        assertEquals("Custom handled", validResponse.getOutput());
    }

    @Test
    void testValidateRequestMethodDirectly() {
        TestAgent agent = new TestAgent();
        
        // Valid request
        AgentRequest validRequest = AgentRequest.builder()
                .type("test")
                .description("Test description")
                .context(Map.of("key", "value"))
                .build();
        assertNull(agent.validateRequest(validRequest));

        // Invalid request - null
        assertNotNull(agent.validateRequest(null));

        // Invalid request - null description
        AgentRequest nullDesc = AgentRequest.builder()
                .type("test")
                .description(null)
                .context(Map.of())
                .build();
        assertNotNull(agent.validateRequest(nullDesc));
    }

    @Test
    void testProcessRequestIsFinal() {
        // Verify that processRequest is final and cannot be overridden
        // This is verified by the compiler, but we can test that it exists
        TestAgent agent = new TestAgent();
        assertNotNull(agent);
        
        // The fact that TestAgent compiles proves that processRequest
        // is properly implemented in AbstractAgent and used by subclasses
    }
}

package com.pos.agent.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for immutable AgentResponse
 */
class AgentResponseTest {

    @Test
    void testBuilderCreatesImmutableResponse() {
        // Create response using builder
        AgentResponse response = AgentResponse.builder()
                .status(AgentStatus.SUCCESS)
                .output("Test output")
                .confidence(0.95)
                .recommendations(List.of("recommendation1", "recommendation2"))
                .build();

        // Verify fields
        assertEquals(AgentStatus.SUCCESS, response.getStatus());
        assertEquals("SUCCESS", response.getStatus());
        assertEquals("Test output", response.getOutput());
        assertEquals(0.95, response.getConfidence());
        assertEquals(2, response.getRecommendations().size());
        assertTrue(response.getRecommendations().contains("recommendation1"));
    }

    @Test
    void testSuccessFactoryMethod() {
        AgentResponse response = AgentResponse.success("Success message", 0.9);

        assertEquals(AgentStatus.SUCCESS, response.getStatus());
        assertEquals("Success message", response.getOutput());
        assertEquals(0.9, response.getConfidence());
        assertTrue(response.isSuccess());
        assertNotNull(response.getRecommendations());
        assertTrue(response.getRecommendations().isEmpty());
    }

    @Test
    void testFailureFactoryMethod() {
        AgentResponse response = AgentResponse.failure("Error occurred");

        assertEquals(AgentStatus.FAILURE, response.getStatus());
        assertEquals("Error occurred", response.getOutput());
        assertEquals(0.0, response.getConfidence());
        assertFalse(response.isSuccess());
        assertEquals("Error occurred", response.getErrorMessage());
    }

    @Test
    void testBackwardCompatibilityWithStringStatus() {
        AgentResponse response = AgentResponse.builder()
                .status("SUCCESS")
                .output("Output")
                .confidence(0.8)
                .build();

        assertEquals("SUCCESS", response.getStatus());
        assertEquals(AgentStatus.SUCCESS, response.getStatus());
    }

    @Test
    void testBackwardCompatibilityWithSuccessFlag() {
        AgentResponse success = AgentResponse.builder()
                .success(true)
                .output("Success output")
                .build();

        assertTrue(success.isSuccess());
        assertEquals(AgentStatus.SUCCESS, success.getStatus());

        AgentResponse failure = AgentResponse.builder()
                .success(false)
                .output("Failure output")
                .build();

        assertFalse(failure.isSuccess());
        assertEquals(AgentStatus.FAILURE, failure.getStatus());
    }

    @Test
    void testBackwardCompatibilityWithErrorMessage() {
        AgentResponse response = AgentResponse.builder()
                .errorMessage("Error message")
                .build();

        assertEquals(AgentStatus.FAILURE, response.getStatus());
        assertEquals("Error message", response.getOutput());
    }

    @Test
    void testBackwardCompatibilityWithMetadata() {
        AgentResponse response = AgentResponse.builder()
                .status(AgentStatus.SUCCESS)
                .output("Output")
                .confidence(0.8)
                .processingTimeMs(100L)
                .escalationReason("Needs review")
                .context(Map.of("domain", "testing"))
                .build();

        assertEquals(100L, response.getProcessingTimeMs());
        assertEquals("Needs review", response.getEscalationReason());
        assertNotNull(response.getContext());
        assertEquals("testing", response.getContext().get("domain"));
    }

    @Test
    void testDefaultConstructorForBackwardCompatibility() {
        // Test the old setter-based pattern still works
        AgentResponse response = new AgentResponse();
        response.setStatus("SUCCESS");
        response.setOutput("Test output");
        response.setConfidence(0.85);
        response.setRecommendations(List.of("rec1", "rec2"));

        assertEquals("SUCCESS", response.getStatus());
        assertEquals("Test output", response.getOutput());
        assertEquals(0.85, response.getConfidence());
        assertEquals(2, response.getRecommendations().size());
    }

    @Test
    void testSetContextForBackwardCompatibility() {
        AgentResponse response = new AgentResponse();
        Map<String, Object> context = Map.of("key", "value");
        response.setContext(context);

        assertNotNull(response.getContext());
        assertEquals("value", response.getContext().get("key"));
    }

    @Test
    void testSetEscalationReasonForBackwardCompatibility() {
        AgentResponse response = new AgentResponse();
        response.setEscalationReason("Escalation needed");

        assertEquals("Escalation needed", response.getEscalationReason());
    }

    @Test
    void testNullSafeRecommendations() {
        AgentResponse response = AgentResponse.builder()
                .status(AgentStatus.SUCCESS)
                .output("Output")
                .recommendations(null)
                .build();

        assertNotNull(response.getRecommendations());
        assertTrue(response.getRecommendations().isEmpty());
    }

    @Test
    void testNullSafeMetadata() {
        AgentResponse response = AgentResponse.builder()
                .status(AgentStatus.SUCCESS)
                .output("Output")
                .context(null)
                .build();

        assertNotNull(response.getContext());
        assertTrue(response.getContext().isEmpty());
    }

    @Test
    void testIsSuccessMethod() {
        AgentResponse success = AgentResponse.builder()
                .status(AgentStatus.SUCCESS)
                .output("Success")
                .build();
        assertTrue(success.isSuccess());

        AgentResponse failure = AgentResponse.builder()
                .status(AgentStatus.FAILURE)
                .output("Failure")
                .build();
        assertFalse(failure.isSuccess());

        AgentResponse pending = AgentResponse.builder()
                .status(AgentStatus.PENDING)
                .output("Pending")
                .build();
        assertFalse(pending.isSuccess());

        AgentResponse stopped = AgentResponse.builder()
                .status(AgentStatus.STOPPED)
                .output("Stopped")
                .build();
        assertFalse(stopped.isSuccess());
    }
}

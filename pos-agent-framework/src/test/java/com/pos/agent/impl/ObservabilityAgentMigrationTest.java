package com.pos.agent.impl;

import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.core.AgentStatus;
import com.pos.agent.framework.model.AgentType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for migrated ObservabilityAgent using AbstractAgent
 */
class ObservabilityAgentMigrationTest {

    private ObservabilityAgent agent;

    @BeforeEach
    void setUp() {
        agent = new ObservabilityAgent();
    }

    @Test
    void testValidRequest() {
        AgentRequest request = AgentRequest.builder()
                .type("observability")
                .description("How to implement monitoring?")
                .context(Map.of("domain", "observability"))
                .build();

        AgentResponse response = agent.processRequest(request);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals("SUCCESS", response.getStatus());
        assertEquals("Observability guidance: How to implement monitoring?", response.getOutput());
        assertEquals(0.8, response.getConfidence());
        assertNotNull(response.getRecommendations());
        assertEquals(3, response.getRecommendations().size());
        assertTrue(response.getRecommendations().contains("add monitoring"));
    }

    @Test
    void testNullRequestValidation() {
        AgentResponse response = agent.processRequest(null);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals("FAILURE", response.getStatus());
        assertEquals("Invalid request: request is null", response.getOutput());
        assertEquals(0.0, response.getConfidence());
    }

    @Test
    void testNullDescriptionValidation() {
        assertThrows(IllegalStateException.class, () -> {
            AgentRequest.builder()
                    .type("observability")
                    .description(null)
                    .context(Map.of("domain", "observability"))
                    .build();
        });
    }

    @Test
    void testEmptyDescriptionValidation() {
        assertThrows(IllegalStateException.class, () -> {
            AgentRequest.builder()
                    .type("observability")
                    .description("   ")
                    .context(Map.of("domain", "observability"))
                    .build();
        });
    }

    @Test
    void testNullContextValidation() {
        assertThrows(IllegalStateException.class, () -> {
            AgentRequest.builder()
                    .type("observability")
                    .description("How to monitor?")
                    .context(null)
                    .build();
        });
    }

    @Test
    void testInvalidTypeValidation() {
        AgentRequest request = AgentRequest.builder()
                .type("invalid-type")
                .description("How to monitor?")
                .context(Map.of("domain", "observability"))
                .build();

        AgentResponse response = agent.processRequest(request);

        assertFalse(response.isSuccess());
        assertEquals("Invalid request: invalid type", response.getOutput());
    }

    @Test
    void testValidationInheritedFromAbstractAgent() {
        // Verify ObservabilityAgent uses AbstractAgent's validation
        AgentRequest validRequest = AgentRequest.builder()
                .type("observability")
                .description("Valid description")
                .context(Map.of("key", "value"))
                .build();

        AgentResponse validResponse = agent.processRequest(validRequest);
        assertTrue(validResponse.isSuccess());

        // All validation tests above confirm AbstractAgent validation is working
    }

    @Test
    void testOutputContainsDescription() {
        String description = "Implement distributed tracing";
        AgentRequest request = AgentRequest.builder()
                .type("observability")
                .description(description)
                .context(Map.of("tracing", "distributed"))
                .build();

        AgentResponse response = agent.processRequest(request);

        assertTrue(response.isSuccess());
        assertTrue(response.getOutput().contains(description));
        assertTrue(response.getOutput().startsWith("Observability guidance:"));
    }

    @Test
    void testRecommendationsProvided() {
        AgentRequest request = AgentRequest.builder()
                .type("observability")
                .description("Logging strategy")
                .context(Map.of("area", "logging"))
                .build();

        AgentResponse response = agent.processRequest(request);

        assertNotNull(response.getRecommendations());
        assertFalse(response.getRecommendations().isEmpty());
        assertTrue(response.getRecommendations().contains("implement pattern"));
        assertTrue(response.getRecommendations().contains("configure system"));
        assertTrue(response.getRecommendations().contains("add monitoring"));
    }

    @Test
    void testConfidenceLevel() {
        AgentRequest request = AgentRequest.builder()
                .type("observability")
                .description("Metrics collection")
                .context(Map.of("focus", "metrics"))
                .build();

        AgentResponse response = agent.processRequest(request);

        assertEquals(0.8, response.getConfidence());
        assertTrue(response.getConfidence() > 0.0);
        assertTrue(response.getConfidence() <= 1.0);
    }

    @Test
    void testDifferentFromTestingAgent() {
        // Verify ObservabilityAgent produces different output than TestingAgent
        AgentRequest request = AgentRequest.builder()
                .type("observability")
                .description("Common request")
                .context(Map.of("domain", "test"))
                .build();

        AgentResponse obsResponse = agent.processRequest(request);
        TestingAgent testingAgent = new TestingAgent();
        AgentResponse testResponse = testingAgent.processRequest(
                AgentRequest.builder()
                        .type("testing")
                        .description("Common request")
                        .context(Map.of("domain", "test"))
                        .build());

        // Both should succeed but have different output
        assertTrue(obsResponse.isSuccess());
        assertTrue(testResponse.isSuccess());
        assertNotEquals(obsResponse.getOutput(), testResponse.getOutput());
        assertTrue(obsResponse.getOutput().contains("Observability guidance:"));
        assertTrue(testResponse.getOutput().contains("Testing pattern recommendation:"));
    }

    @Test
    void testMetricsAndMonitoringGuidance() {
        AgentRequest request = AgentRequest.builder()
                .type("observability")
                .description("Set up Prometheus metrics")
                .context(Map.of("tool", "prometheus"))
                .build();

        AgentResponse response = agent.processRequest(request);

        assertTrue(response.isSuccess());
        assertTrue(response.getOutput().contains("Set up Prometheus metrics"));
        assertEquals(0.8, response.getConfidence());
    }
}

package com.pos.agent.impl;

import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for migrated TestingAgent using AbstractAgent
 */
class TestingAgentMigrationTest {

    private TestingAgent agent;

    @BeforeEach
    void setUp() {
        agent = new TestingAgent();
    }

    @Test
    void testValidRequest() {
        AgentRequest request = AgentRequest.builder()
                .type("testing")
                .description("How to implement unit tests?")
                .context(Map.of("domain", "testing"))
                .build();

        AgentResponse response = agent.processRequest(request);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals("SUCCESS", response.getStatus());
        assertEquals("Testing pattern recommendation: How to implement unit tests?", response.getOutput());
        assertEquals(0.8, response.getConfidence());
        assertNotNull(response.getRecommendations());
        assertEquals(3, response.getRecommendations().size());
        assertTrue(response.getRecommendations().contains("implement pattern"));
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
                    .type("testing")
                    .description(null)
                    .context(Map.of("domain", "testing"))
                    .build();
        });
    }

    @Test
    void testEmptyDescriptionValidation() {
        assertThrows(IllegalStateException.class, () -> {
            AgentRequest.builder()
                    .type("testing")
                    .description("   ")
                    .context(Map.of("domain", "testing"))
                    .build();
        });
    }

    @Test
    void testNullContextValidation() {
        assertThrows(IllegalStateException.class, () -> {
            AgentRequest.builder()
                    .type("testing")
                    .description("How to test?")
                    .context(null)
                    .build();
        });
    }

    @Test
    void testInvalidTypeValidation() {
        AgentRequest request = AgentRequest.builder()
                .type("invalid-type")
                .description("How to test?")
                .context(Map.of("domain", "testing"))
                .build();

        AgentResponse response = agent.processRequest(request);

        assertFalse(response.isSuccess());
        assertEquals("Invalid request: invalid type", response.getOutput());
    }

    @Test
    void testValidationInheritedFromAbstractAgent() {
        // Verify TestingAgent uses AbstractAgent's validation
        AgentRequest validRequest = AgentRequest.builder()
                .type("testing")
                .description("Valid description")
                .context(Map.of("key", "value"))
                .build();

        AgentResponse validResponse = agent.processRequest(validRequest);
        assertTrue(validResponse.isSuccess());

        // All validation tests above confirm AbstractAgent validation is working
    }

    @Test
    void testOutputContainsDescription() {
        String description = "Write integration tests for REST API";
        AgentRequest request = AgentRequest.builder()
                .type("testing")
                .description(description)
                .context(Map.of("api", "REST"))
                .build();

        AgentResponse response = agent.processRequest(request);

        assertTrue(response.isSuccess());
        assertTrue(response.getOutput().contains(description));
    }

    @Test
    void testRecommendationsProvided() {
        AgentRequest request = AgentRequest.builder()
                .type("testing")
                .description("Test automation strategy")
                .context(Map.of("area", "automation"))
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
                .type("testing")
                .description("Testing best practices")
                .context(Map.of("focus", "best practices"))
                .build();

        AgentResponse response = agent.processRequest(request);

        assertEquals(0.8, response.getConfidence());
        assertTrue(response.getConfidence() > 0.0);
        assertTrue(response.getConfidence() <= 1.0);
    }
}

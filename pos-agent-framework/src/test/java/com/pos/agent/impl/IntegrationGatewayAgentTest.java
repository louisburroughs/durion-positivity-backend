package com.pos.agent.impl;

import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.core.AgentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for IntegrationGatewayAgent to verify Agent interface compliance
 * and AbstractAgent integration with proper validation and error handling.
 */
public class IntegrationGatewayAgentTest {

    private IntegrationGatewayAgent agent;

    @BeforeEach
    public void setUp() {
        agent = new IntegrationGatewayAgent();
    }

    @Test
    public void testProcessRequest_ReturnsAgentResponse() {
        // Arrange
        Map<String, Object> context = new HashMap<>();
        context.put("body", "Improve integration capabilities");
        AgentRequest request = AgentRequest.builder()
                .description("Improve integration capabilities")
                .type("integration")
                .context(context)
                .build();

        // Act
        AgentResponse response = agent.processRequest(request);

        // Assert
        assertNotNull(response);
        assertNotNull(response.getOutput());
        assertNotNull(response.getStatusEnum());
    }

    @Test
    public void testProcessRequest_SuccessfulProcessing() {
        // Arrange
        Map<String, Object> context = new HashMap<>();
        context.put("body", "Enhance API integration");
        AgentRequest request = AgentRequest.builder()
                .description("Enhance API integration")
                .type("integration")
                .context(context)
                .build();

        // Act
        AgentResponse response = agent.processRequest(request);

        // Assert
        assertEquals(AgentStatus.SUCCESS, response.getStatusEnum());
        assertTrue(response.isSuccess());
        assertNotNull(response.getOutput());
        assertTrue(response.getOutput().contains("Integration guidance:"));
        assertEquals(0.8, response.getConfidence());
        assertNotNull(response.getRecommendations());
        assertEquals(3, response.getRecommendations().size());
    }

    @Test
    public void testProcessRequest_ValidationError_NullDescription() {
        // Arrange
        Map<String, Object> context = new HashMap<>();

        // Act & Assert
        assertThrows(IllegalStateException.class, () -> {
            AgentRequest.builder()
                    .description(null)
                    .type("integration")
                    .context(context)
                    .build();
        });
    }

    @Test
    public void testProcessRequest_ValidationError_EmptyDescription() {
        // Arrange
        Map<String, Object> context = new HashMap<>();

        // Act & Assert
        assertThrows(IllegalStateException.class, () -> {
            AgentRequest.builder()
                    .description("   ")
                    .type("integration")
                    .context(context)
                    .build();
        });
    }

    @Test
    public void testProcessRequest_ValidationError_NullContext() {
        // Act & Assert
        assertThrows(IllegalStateException.class, () -> {
            AgentRequest.builder()
                    .description("Improve integration capabilities")
                    .type("integration")
                    .context(null)
                    .build();
        });
    }

    @Test
    public void testProcessRequest_ValidationError_InvalidType() {
        // Arrange
        Map<String, Object> context = new HashMap<>();
        context.put("body", "Improve integration capabilities");
        AgentRequest request = AgentRequest.builder()
                .description("Improve integration capabilities")
                .type("invalid")
                .context(context)
                .build();

        // Act
        AgentResponse response = agent.processRequest(request);

        // Assert
        assertEquals(AgentStatus.FAILURE, response.getStatusEnum());
        assertFalse(response.isSuccess());
        assertTrue(response.getOutput().contains("invalid type"));
    }

    @Test
    public void testProcessRequest_SuccessfulResponse_IncludesRecommendations() {
        // Arrange
        Map<String, Object> context = new HashMap<>();
        context.put("body", "Enable service mesh integration");
        AgentRequest request = AgentRequest.builder()
                .description("Enable service mesh integration")
                .type("integration")
                .context(context)
                .build();

        // Act
        AgentResponse response = agent.processRequest(request);

        // Assert
        assertEquals(AgentStatus.SUCCESS, response.getStatusEnum());
        assertTrue(response.getRecommendations().contains("implement pattern"));
        assertTrue(response.getRecommendations().contains("configure system"));
        assertTrue(response.getRecommendations().contains("add monitoring"));
    }

    @Test
    public void testProcessRequest_ValidationError_NullRequest() {
        // Act
        AgentResponse response = agent.processRequest(null);

        // Assert
        assertEquals(AgentStatus.FAILURE, response.getStatusEnum());
        assertFalse(response.isSuccess());
        assertTrue(response.getOutput().contains("request is null"));
    }
}

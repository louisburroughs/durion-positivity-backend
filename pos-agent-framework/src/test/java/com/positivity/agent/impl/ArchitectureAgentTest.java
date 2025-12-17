package com.positivity.agent.impl;

import com.positivity.agent.AgentConsultationRequest;
import com.positivity.agent.AgentGuidanceResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ArchitectureAgent to verify enhanced capabilities for REQ-001
 */
class ArchitectureAgentTest {

    private ArchitectureAgent architectureAgent;

    @BeforeEach
    void setUp() {
        architectureAgent = new ArchitectureAgent();
    }

    @Test
    void testDomainDrivenDesignGuidance() throws ExecutionException, InterruptedException {
        // Test DDD guidance capability
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "architecture",
                "I need guidance on domain boundaries for the POS system with ddd domain boundaries pos system",
                Map.of());

        CompletableFuture<AgentGuidanceResponse> future = architectureAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("BOUNDED CONTEXTS"));
        assertTrue(response.guidance().contains("Catalog Domain"));
        assertTrue(response.guidance().contains("Customer Domain"));
        assertTrue(response.guidance().contains("Order Domain"));
    }

    @Test
    void testMicroserviceBoundaryEnforcement() throws ExecutionException, InterruptedException {
        // Test microservice boundary enforcement
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "architecture",
                "How should I structure microservices for the POS system with microservice boundaries pos services?",
                Map.of());

        CompletableFuture<AgentGuidanceResponse> future = architectureAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("pos-catalog"));
        assertTrue(response.guidance().contains("pos-customer"));
        assertTrue(response.guidance().contains("pos-order"));
        assertTrue(response.guidance().contains("Database-per-Service"));
    }

    @Test
    void testIntegrationPatternSpecification() throws ExecutionException, InterruptedException {
        // Test integration pattern guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "architecture",
                "What integration patterns should I use for the POS system with integration patterns api gateway messaging events?",
                Map.of());

        CompletableFuture<AgentGuidanceResponse> future = architectureAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("API GATEWAY PATTERN"));
        assertTrue(response.guidance().contains("pos-api-gateway"));
        assertTrue(response.guidance().contains("EVENT-DRIVEN ARCHITECTURE"));
        assertTrue(response.guidance().contains("pos-events"));
    }

    @Test
    void testTechnologyStackValidation() throws ExecutionException, InterruptedException {
        // Test technology stack decisions and Java 21 validation
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "architecture",
                "What technology stack should I use for Java development with technology stack java spring boot aws?",
                Map.of());

        CompletableFuture<AgentGuidanceResponse> future = architectureAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("Java 21"));
        assertTrue(response.guidance().contains("SDKMAN"));
        assertTrue(response.guidance().contains("Spring Boot 3.2.6"));
        assertTrue(response.guidance().contains("Spring Cloud 2023.0.1"));
    }

    @Test
    void testAgentCapabilities() {
        // Verify enhanced capabilities
        assertTrue(architectureAgent.getCapabilities().contains("ddd"));
        assertTrue(architectureAgent.getCapabilities().contains("microservices"));
        assertTrue(architectureAgent.getCapabilities().contains("integration-patterns"));
        assertTrue(architectureAgent.getCapabilities().contains("pos-domain"));
        assertTrue(architectureAgent.getCapabilities().contains("technology-stack"));
        assertTrue(architectureAgent.getCapabilities().contains("java21"));
        assertTrue(architectureAgent.getCapabilities().contains("spring-boot"));
        assertTrue(architectureAgent.getCapabilities().contains("aws-patterns"));
    }

    @Test
    void testCanHandleArchitectureRequests() {
        // Test that agent can handle architecture domain requests
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "architecture",
                "General architecture question with architecture guidance",
                Map.of());

        assertTrue(architectureAgent.canHandle(request));
    }

    @Test
    void testCanHandleCapabilityBasedRequests() {
        // Test that agent can handle requests based on capabilities
        AgentConsultationRequest dddRequest = AgentConsultationRequest.create(
                "implementation",
                "I need DDD guidance with domain driven design patterns",
                Map.of());

        assertTrue(architectureAgent.canHandle(dddRequest));

        AgentConsultationRequest microserviceRequest = AgentConsultationRequest.create(
                "deployment",
                "Microservices deployment question with microservices deployment patterns",
                Map.of());

        assertTrue(architectureAgent.canHandle(microserviceRequest));
    }
}
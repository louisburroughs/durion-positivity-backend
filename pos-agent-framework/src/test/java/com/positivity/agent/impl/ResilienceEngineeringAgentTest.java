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
 * Unit tests for ResilienceEngineeringAgent to verify resilience engineering
 * guidance capabilities
 * Validates: Requirements REQ-015.1, REQ-015.2, REQ-015.3, REQ-015.4, REQ-015.5
 */
class ResilienceEngineeringAgentTest {

    private ResilienceEngineeringAgent resilienceAgent;

    @BeforeEach
    void setUp() {
        resilienceAgent = new ResilienceEngineeringAgent();
    }

    @Test
    void testResilience4jCircuitBreakerGuidance() throws ExecutionException, InterruptedException {
        // Test Resilience4j circuit breaker configuration guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "resilience",
                "How to configure Resilience4j circuit breakers with resilience4j circuit breaker spring boot?",
                Map.of("service", "pos-api-gateway"));

        CompletableFuture<AgentGuidanceResponse> future = resilienceAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("Resilience4j Circuit Breaker"));
        assertTrue(response.guidance().contains("@CircuitBreaker"));
        assertTrue(response.guidance().contains("spring-boot-starter-aop"));
        assertTrue(response.guidance().contains("failure-rate-threshold"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testHystrixCircuitBreakerGuidance() throws ExecutionException, InterruptedException {
        // Test Hystrix circuit breaker configuration guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "resilience",
                "How to configure Hystrix circuit breakers with hystrix circuit breaker netflix?",
                Map.of("service", "pos-order"));

        CompletableFuture<AgentGuidanceResponse> future = resilienceAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("Hystrix Circuit Breaker"));
        assertTrue(response.guidance().contains("@HystrixCommand"));
        assertTrue(response.guidance().contains("spring-cloud-starter-netflix-hystrix"));
        assertTrue(response.guidance().contains("fallbackMethod"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testSpringCloudCircuitBreakerGuidance() throws ExecutionException, InterruptedException {
        // Test Spring Cloud Circuit Breaker configuration guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "resilience",
                "How to configure Spring Cloud Circuit Breaker with spring cloud circuit breaker abstraction?",
                Map.of("service", "pos-customer"));

        CompletableFuture<AgentGuidanceResponse> future = resilienceAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("Spring Cloud Circuit Breaker"));
        assertTrue(response.guidance().contains("CircuitBreakerFactory"));
        assertTrue(response.guidance().contains("spring-cloud-starter-circuitbreaker"));
        assertTrue(response.guidance().contains("run() and fallback()"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testRetryMechanismGuidance() throws ExecutionException, InterruptedException {
        // Test retry mechanism with exponential backoff guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "resilience",
                "How to implement retry mechanisms with exponential backoff with retry exponential backoff jitter?",
                Map.of("service", "pos-inventory"));

        CompletableFuture<AgentGuidanceResponse> future = resilienceAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("Retry Mechanisms"));
        assertTrue(response.guidance().contains("@Retryable"));
        assertTrue(response.guidance().contains("exponential backoff"));
        assertTrue(response.guidance().contains("jitter"));
        assertTrue(response.guidance().contains("@Recover"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testBulkheadPatternGuidance() throws ExecutionException, InterruptedException {
        // Test bulkhead pattern implementation guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "resilience",
                "How to implement bulkhead patterns for resource isolation with bulkhead pattern resource isolation?",
                Map.of("service", "pos-catalog"));

        CompletableFuture<AgentGuidanceResponse> future = resilienceAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("Bulkhead Pattern"));
        assertTrue(response.guidance().contains("@Bulkhead"));
        assertTrue(response.guidance().contains("resource isolation"));
        assertTrue(response.guidance().contains("thread pool"));
        assertTrue(response.guidance().contains("semaphore"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testRateLimitingGuidance() throws ExecutionException, InterruptedException {
        // Test rate limiting implementation guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "resilience",
                "How to implement rate limiting for API protection with rate limiting api protection throttling?",
                Map.of("service", "pos-api-gateway"));

        CompletableFuture<AgentGuidanceResponse> future = resilienceAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("Rate Limiting Implementation"));
        assertTrue(response.guidance().contains("@RateLimiter"));
        assertTrue(response.guidance().contains("token bucket"));
        assertTrue(response.guidance().contains("sliding window"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testTimeoutConfigurationGuidance() throws ExecutionException, InterruptedException {
        // Test timeout configuration guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "resilience",
                "How to configure timeouts for microservices communication with timeout configuration microservices?",
                Map.of("service", "pos-price"));

        CompletableFuture<AgentGuidanceResponse> future = resilienceAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("Timeout Configuration"));
        assertTrue(response.guidance().contains("@TimeLimiter"));
        assertTrue(response.guidance().contains("RestTemplate"));
        assertTrue(response.guidance().contains("WebClient"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testChaosEngineeringGuidance() throws ExecutionException, InterruptedException {
        // Test chaos engineering and failure injection guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "resilience",
                "How to implement chaos engineering for resilience testing with chaos engineering failure injection?",
                Map.of("service", "pos-order"));

        CompletableFuture<AgentGuidanceResponse> future = resilienceAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("Chaos Engineering"));
        assertTrue(response.guidance().contains("failure injection"));
        assertTrue(response.guidance().contains("Chaos Monkey"));
        assertTrue(response.guidance().contains("resilience testing"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testHealthMonitoringGuidance() throws ExecutionException, InterruptedException {
        // Test health monitoring and failure rate tracking guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "resilience",
                "How to implement health monitoring and failure rate tracking with health monitoring failure tracking?",
                Map.of("service", "pos-accounting"));

        CompletableFuture<AgentGuidanceResponse> future = resilienceAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("Health Monitoring"));
        assertTrue(response.guidance().contains("HealthIndicator"));
        assertTrue(response.guidance().contains("failure rate tracking"));
        assertTrue(response.guidance().contains("Micrometer"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testFallbackPatternGuidance() throws ExecutionException, InterruptedException {
        // Test fallback pattern implementation guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "resilience",
                "How to implement fallback patterns for graceful degradation with fallback pattern graceful degradation?",
                Map.of("service", "pos-customer"));

        CompletableFuture<AgentGuidanceResponse> future = resilienceAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("Fallback Pattern"));
        assertTrue(response.guidance().contains("graceful degradation"));
        assertTrue(response.guidance().contains("fallback method"));
        assertTrue(response.guidance().contains("default response"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testAgentCapabilities() {
        // Verify agent capabilities
        assertTrue(resilienceAgent.getCapabilities().contains("circuit-breakers"));
        assertTrue(resilienceAgent.getCapabilities().contains("retry-mechanisms"));
        assertTrue(resilienceAgent.getCapabilities().contains("bulkhead-pattern"));
        assertTrue(resilienceAgent.getCapabilities().contains("rate-limiting"));
        assertTrue(resilienceAgent.getCapabilities().contains("timeout-configuration"));
        assertTrue(resilienceAgent.getCapabilities().contains("chaos-engineering"));
        assertTrue(resilienceAgent.getCapabilities().contains("health-monitoring"));
        assertTrue(resilienceAgent.getCapabilities().contains("failure-injection"));
        assertTrue(resilienceAgent.getCapabilities().contains("resilience4j"));
        assertTrue(resilienceAgent.getCapabilities().contains("hystrix"));
        assertTrue(resilienceAgent.getCapabilities().contains("spring-cloud-cb"));
    }

    @Test
    void testCanHandleResilienceRequests() {
        // Test that agent can handle resilience domain requests
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "resilience",
                "General resilience engineering question with resilience patterns",
                Map.of());

        assertTrue(resilienceAgent.canHandle(request));
    }

    @Test
    void testCanHandleCapabilityBasedRequests() {
        // Test that agent can handle requests based on capabilities
        AgentConsultationRequest circuitBreakerRequest = AgentConsultationRequest.create(
                "implementation",
                "I need circuit breaker guidance with circuit breaker resilience4j",
                Map.of());

        assertTrue(resilienceAgent.canHandle(circuitBreakerRequest));

        AgentConsultationRequest chaosRequest = AgentConsultationRequest.create(
                "testing",
                "Chaos engineering implementation question with chaos engineering failure injection",
                Map.of());

        assertTrue(resilienceAgent.canHandle(chaosRequest));
    }

    @Test
    void testAgentMetadata() {
        // Verify agent metadata
        assertEquals("resilience-engineering-agent", resilienceAgent.getId());
        assertEquals("Resilience Engineering Agent", resilienceAgent.getName());
        assertEquals("resilience", resilienceAgent.getDomain());
        assertTrue(resilienceAgent.getDependencies().contains("observability-agent"));
        assertTrue(resilienceAgent.getDependencies().contains("testing-agent"));
    }
}
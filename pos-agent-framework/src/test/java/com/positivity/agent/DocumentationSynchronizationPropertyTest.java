package com.positivity.agent;

import com.positivity.agent.impl.DocumentationAgent;
import com.positivity.agent.registry.AgentRegistry;
import com.positivity.agent.registry.DefaultAgentRegistry;
import net.jqwik.api.*;
import net.jqwik.api.constraints.NotEmpty;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for Documentation Agent synchronization capabilities.
 * 
 * **Feature: agent-structure, Property 11: Documentation synchronization**
 * **Validates: Requirements REQ-009.2, REQ-009.3**
 * 
 * Tests that the Documentation Agent ensures documentation completeness
 * and synchronization with code for API and technical documentation requests.
 */
class DocumentationSynchronizationPropertyTest {

    private AgentRegistry registry;
    private DocumentationAgent documentationAgent;

    @BeforeEach
    void setUp() {
        registry = new DefaultAgentRegistry();
        documentationAgent = new DocumentationAgent();
        registry.registerAgent(documentationAgent);
    }

    private void ensureSetup() {
        if (documentationAgent == null) {
            setUp();
        }
    }

    /**
     * Property 11: Documentation synchronization
     * 
     * For any API or technical documentation request, the system should ensure
     * documentation completeness and synchronization with code.
     * 
     * Validates: Requirements REQ-009.2, REQ-009.3
     */
    @Property(tries = 100)
    void documentationSynchronization(
            @ForAll("documentationRequests") AgentConsultationRequest request) {

        // Ensure setup is complete
        ensureSetup();

        // Given: A documentation agent is available
        assertThat(documentationAgent.isAvailable())
                .describedAs("Documentation agent should be available")
                .isTrue();

        // When: Making a documentation request
        CompletableFuture<AgentGuidanceResponse> responseFuture = documentationAgent.provideGuidance(request);
        AgentGuidanceResponse response = responseFuture.join();

        // Then: The system should provide successful guidance
        assertThat(response.isSuccessful())
                .describedAs("Documentation agent should provide successful guidance for: %s", request.query())
                .isTrue();

        // And: Should provide documentation guidance
        String guidance = response.guidance().toLowerCase();
        assertThat(guidance)
                .as("Documentation guidance should be comprehensive")
                .isNotEmpty()
                .hasSizeGreaterThan(100);

        // And: Should include documentation-related terms
        assertThat(guidance)
                .as("Documentation guidance should include documentation practices")
                .containsAnyOf("documentation", "openapi", "swagger", "rest-docs", "javadoc",
                        "readme", "api", "technical", "synchronization", "validation",
                        "maintenance", "generation", "formatting");
    }

    /**
     * Tests API documentation synchronization guidance.
     * Validates REQ-009.2: Comprehensive API documentation and synchronization
     */
    @Property(tries = 100)
    void apiDocumentationSynchronizationGuidance(
            @ForAll("apiDocumentationRequests") AgentConsultationRequest request) {

        // Ensure setup is complete
        ensureSetup();

        // When: Making an API documentation request
        CompletableFuture<AgentGuidanceResponse> responseFuture = documentationAgent.provideGuidance(request);
        AgentGuidanceResponse response = responseFuture.join();

        // Then: The response should provide API documentation guidance
        assertThat(response.isSuccessful()).isTrue();
        String guidance = response.guidance().toLowerCase();

        // And: Should include API-specific synchronization practices
        if (request.query().toLowerCase().contains("api") ||
                request.query().toLowerCase().contains("openapi") ||
                request.query().toLowerCase().contains("swagger")) {
            assertThat(guidance)
                    .as("API documentation guidance should include synchronization practices")
                    .containsAnyOf("openapi", "swagger", "spring rest docs", "api documentation",
                            "endpoint", "schema");
        }

        // And: Should address synchronization mechanisms
        assertThat(guidance)
                .as("API documentation should address synchronization mechanisms")
                .containsAnyOf("automated", "generation", "ci/cd", "gradle", "maven", "springdoc");
    }

    /**
     * Tests documentation completeness validation guidance.
     * Validates REQ-009.3: Documentation completeness and accuracy validation
     */
    @Property(tries = 100)
    void documentationCompletenessValidation(
            @ForAll("validationRequests") AgentConsultationRequest request) {

        // Ensure setup is complete
        ensureSetup();

        // When: Making a documentation validation request
        CompletableFuture<AgentGuidanceResponse> responseFuture = documentationAgent.provideGuidance(request);
        AgentGuidanceResponse response = responseFuture.join();

        // Then: The response should provide validation guidance
        assertThat(response.isSuccessful()).isTrue();
        String guidance = response.guidance().toLowerCase();

        // And: Should include completeness validation practices
        if (request.query().toLowerCase().contains("validation") ||
                request.query().toLowerCase().contains("completeness") ||
                request.query().toLowerCase().contains("accuracy")) {
            assertThat(guidance)
                    .as("Documentation validation should include completeness practices")
                    .containsAnyOf("completeness", "accuracy", "validation", "audit", "review", "quality");
        }

        // And: Should provide specific validation techniques
        assertThat(guidance)
                .as("Documentation validation should provide specific techniques")
                .containsAnyOf("checklist", "automated", "metrics", "coverage", "freshness", "broken links");
    }

    /**
     * Tests documentation guidance performance requirements.
     * Ensures the Documentation Agent meets performance specifications
     */
    @Property(tries = 100)
    void documentationGuidancePerformance(
            @ForAll("documentationRequests") AgentConsultationRequest request) {

        // Ensure setup is complete
        ensureSetup();

        // When: Making a documentation request (with timing)
        long startTime = System.currentTimeMillis();
        CompletableFuture<AgentGuidanceResponse> responseFuture = documentationAgent.provideGuidance(request);
        AgentGuidanceResponse response = responseFuture.join();
        long endTime = System.currentTimeMillis();
        Duration responseTime = Duration.ofMillis(endTime - startTime);

        // Then: The response should meet performance requirements
        assertThat(response.isSuccessful()).isTrue();
        assertThat(responseTime)
                .as("Documentation guidance should respond within 3 seconds")
                .isLessThanOrEqualTo(Duration.ofSeconds(3));

        // And: Should meet confidence requirements
        assertThat(response.confidence())
                .as("Documentation guidance should have high confidence")
                .isGreaterThanOrEqualTo(0.90);

        // And: Should provide comprehensive guidance
        assertThat(response.guidance().length())
                .as("Documentation guidance should be comprehensive")
                .isGreaterThan(100);
    }

    // Test data generators

    @Provide
    Arbitrary<AgentConsultationRequest> documentationRequests() {
        return Arbitraries.oneOf(
                // API documentation requests
                createRequest("order-management", "api documentation"),
                createRequest("inventory-system", "openapi specification"),
                createRequest("payment-service", "swagger docs"),
                createRequest("customer-service", "rest api docs"),

                // Technical documentation requests
                createRequest("catalog-service", "technical documentation"),
                createRequest("security-service", "readme file"),
                createRequest("gateway-service", "architectural docs"),
                createRequest("notification-service", "system documentation"),

                // Validation and completeness requests
                createRequest("reporting-service", "documentation validation"),
                createRequest("analytics-service", "completeness check"),
                createRequest("audit-service", "accuracy review"),
                createRequest("monitoring-service", "documentation audit"),

                // Synchronization requests
                createRequest("integration-service", "documentation synchronization"),
                createRequest("workflow-service", "keep docs updated"),
                createRequest("data-service", "sync with code"),
                createRequest("search-service", "automated documentation"));
    }

    @Provide
    Arbitrary<AgentConsultationRequest> apiDocumentationRequests() {
        return Arbitraries.oneOf(
                createRequest("order-api", "api documentation"),
                createRequest("inventory-api", "openapi spec"),
                createRequest("payment-api", "swagger documentation"),
                createRequest("customer-api", "rest api docs"),
                createRequest("catalog-api", "endpoint documentation"),
                createRequest("security-api", "api synchronization"),
                createRequest("gateway-api", "spring rest docs"),
                createRequest("notification-api", "api generation"));
    }

    @Provide
    Arbitrary<AgentConsultationRequest> validationRequests() {
        return Arbitraries.oneOf(
                createRequest("validation-service", "documentation validation"),
                createRequest("quality-service", "completeness check"),
                createRequest("audit-service", "accuracy validation"),
                createRequest("review-service", "documentation audit"),
                createRequest("metrics-service", "quality review"),
                createRequest("compliance-service", "doc completeness"),
                createRequest("standards-service", "validation checklist"),
                createRequest("governance-service", "documentation quality"));
    }

    private Arbitrary<AgentConsultationRequest> createRequest(String domain, String query) {
        return Arbitraries.just(AgentConsultationRequest.create(
                domain,
                query,
                Map.of("context", "test", "type", "documentation")));
    }

    // Unit tests for specific functionality

    @Test
    void shouldProvideApiDocumentationGuidance() {
        // Given: An API documentation request
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "order-management",
                "api documentation synchronization",
                Map.of("context", "test"));

        // When: The Documentation Agent processes the request
        CompletableFuture<AgentGuidanceResponse> responseFuture = documentationAgent.provideGuidance(request);
        AgentGuidanceResponse response = responseFuture.join();

        // Then: Should provide comprehensive API documentation guidance
        assertThat(response.isSuccessful()).isTrue();
        String guidance = response.guidance().toLowerCase();
        assertThat(guidance)
                .contains("openapi")
                .contains("synchronization")
                .contains("spring rest docs");
    }

    @Test
    void shouldProvideValidationGuidance() {
        // Given: A documentation validation request
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "inventory-management",
                "documentation completeness validation",
                Map.of("context", "test"));

        // When: The Documentation Agent processes the request
        CompletableFuture<AgentGuidanceResponse> responseFuture = documentationAgent.provideGuidance(request);
        AgentGuidanceResponse response = responseFuture.join();

        // Then: Should provide validation guidance
        assertThat(response.isSuccessful()).isTrue();
        String guidance = response.guidance().toLowerCase();
        assertThat(guidance)
                .contains("completeness")
                .contains("validation")
                .contains("audit");
    }

    @Test
    void shouldProvideTechnicalDocumentationGuidance() {
        // Given: A technical documentation request
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "payment-processing",
                "technical documentation standards",
                Map.of("context", "test"));

        // When: The Documentation Agent processes the request
        CompletableFuture<AgentGuidanceResponse> responseFuture = documentationAgent.provideGuidance(request);
        AgentGuidanceResponse response = responseFuture.join();

        // Then: Should provide technical documentation guidance
        assertThat(response.isSuccessful()).isTrue();
        String guidance = response.guidance().toLowerCase();
        assertThat(guidance)
                .contains("readme")
                .contains("technical")
                .contains("documentation");
    }
}
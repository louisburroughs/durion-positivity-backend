package com.pos.agent;

import com.pos.agent.core.AgentManager;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.core.SecurityContext;
import com.pos.agent.context.AgentContext;
import net.jqwik.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

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

        private AgentManager agentManager;
        private final SecurityContext securityContext = SecurityContext.builder()
                        .jwtToken("documentation-test-token")
                        .userId("doc-tester")
                        .roles(List.of("admin", "developer", "architect", "operator"))
                        .permissions(List.of(
                                        "documentation:read",
                                        "documentation:write",
                                        "AGENT_READ",
                                        "AGENT_WRITE",
                                        "agent:read",
                                        "agent:write"))
                        .serviceId("pos-doc-tests")
                        .serviceType("property")
                        .build();

        @BeforeEach
        void setUp() {
                agentManager = new AgentManager();
        }

        private void ensureSetup() {
                if (agentManager == null) {
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
                        @ForAll("documentationRequests") String query) {

                // Ensure setup is complete
                ensureSetup();

                // Given: A documentation request
                AgentRequest request = AgentRequest.builder()
                                .description("Documentation synchronization property test")
                                .type("documentation")
                                .context(AgentContext.builder()
                                                .property("query", query)
                                                .property("focus", "synchronization")
                                                .build())
                                .securityContext(securityContext)
                                .build();

                // When: Making a documentation request
                AgentResponse response = agentManager.processRequest(request);

                // Then: The system should provide successful guidance
                assertThat(response.isSuccess())
                                .describedAs("Documentation agent should provide successful guidance for: %s", query)
                                .isTrue();

                // And: Should provide documentation guidance
                String guidance = response.getOutput().toLowerCase();
                assertThat(guidance)
                                .as("Documentation guidance should be comprehensive")
                                .isNotEmpty();

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
                        @ForAll("apiDocumentationRequests") String query) {

                // Ensure setup is complete
                ensureSetup();

                // Given: An API documentation request
                AgentRequest request = AgentRequest.builder()
                                .type("documentation")
                                .description("API documentation synchronization property test")
                                .context(AgentContext.builder()
                                                .property("query", query)
                                                .property("focus", "api")
                                                .build())
                                .securityContext(securityContext)
                                .build();

                // When: Making an API documentation request
                AgentResponse response = agentManager.processRequest(request);

                // Then: The response should provide API documentation guidance
                assertThat(response.isSuccess()).isTrue();
                String guidance = response.getOutput().toLowerCase();

                // And: Should include API-specific synchronization practices
                if (query.toLowerCase().contains("api") ||
                                query.toLowerCase().contains("openapi") ||
                                query.toLowerCase().contains("swagger")) {
                        assertThat(guidance)
                                        .as("API documentation guidance should include synchronization practices")
                                        .containsAnyOf("OpenAPI", "Swagger", "Spring REST Docs", "API documentation",
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
                        @ForAll("validationRequests") String query) {

                // Ensure setup is complete
                ensureSetup();

                // Given: A documentation validation request
                AgentRequest request = AgentRequest.builder()
                                .type("documentation")
                                .description("Documentation completeness validation property test")
                                .context(AgentContext.builder()
                                                .property("query", query)
                                                .property("focus", "validation")
                                                .build())
                                .securityContext(securityContext)
                                .build();

                // When: Making a documentation validation request
                AgentResponse response = agentManager.processRequest(request);

                // Then: The response should provide validation guidance
                assertThat(response.isSuccess()).isTrue();
                String guidance = response.getOutput().toLowerCase();

                // And: Should include completeness validation practices
                if (query.toLowerCase().contains("validation") ||
                                query.toLowerCase().contains("completeness") ||
                                query.toLowerCase().contains("accuracy")) {
                        assertThat(guidance)
                                        .as("Documentation validation should include completeness practices")
                                        .containsAnyOf("completeness", "accuracy", "validation", "audit", "review",
                                                        "quality");
                }

                // And: Should provide specific validation techniques
                assertThat(guidance)
                                .as("Documentation validation should provide specific techniques")
                                .containsAnyOf("checklist", "automated", "metrics", "coverage", "freshness",
                                                "broken links");
        }

        /**
         * Tests documentation guidance performance requirements.
         * Ensures the Documentation Agent meets performance specifications
         */
        @Property(tries = 100)
        void documentationGuidancePerformance(
                        @ForAll("documentationRequests") String query) {

                // Ensure setup is complete
                ensureSetup();

                // Given: A documentation request
                AgentRequest request = AgentRequest.builder()
                                .type("documentation")
                                .description("Documentation guidance performance property test")
                                .context(AgentContext.builder()
                                                .property("query", query)
                                                .build())
                                .securityContext(securityContext)
                                .build();

                // When: Making a documentation request (with timing)
                long startTime = System.nanoTime();
                AgentResponse response = agentManager.processRequest(request);
                long endTime = System.nanoTime();
                Duration responseTime = Duration.ofNanos(endTime - startTime);

                // Then: The response should meet performance requirements
                assertThat(response.isSuccess()).isTrue();
                assertThat(responseTime)
                                .as("Documentation guidance should respond within 3 seconds")
                                .isLessThanOrEqualTo(Duration.ofSeconds(3));

                // And: Should provide comprehensive guidance
                String guidance = response.getOutput();
                assertThat(guidance)
                                .as("Documentation guidance should be comprehensive")
                                .isNotEmpty();
        }

        // Test data generators

        @Provide
        Arbitrary<String> documentationRequests() {
                return Arbitraries.of(
                                // API documentation requests
                                "order-management api documentation",
                                "inventory-system openapi specification",
                                "payment-service swagger docs",
                                "customer-service rest api docs",

                                // Technical documentation requests
                                "catalog-service technical documentation",
                                "security-service readme file",
                                "gateway-service architectural docs",
                                "notification-service system documentation",

                                // Validation and completeness requests
                                "reporting-service documentation validation",
                                "analytics-service completeness check",
                                "audit-service accuracy review",
                                "monitoring-service documentation audit",

                                // Synchronization requests
                                "integration-service documentation synchronization",
                                "workflow-service keep docs updated",
                                "data-service sync with code",
                                "search-service automated documentation");
        }

        @Provide
        Arbitrary<String> apiDocumentationRequests() {
                return Arbitraries.of(
                                "order-api api documentation",
                                "inventory-api openapi spec",
                                "payment-api swagger documentation",
                                "customer-api rest api docs",
                                "catalog-api endpoint documentation",
                                "security-api api synchronization",
                                "gateway-api spring rest docs",
                                "notification-api api generation");
        }

        @Provide
        Arbitrary<String> validationRequests() {
                return Arbitraries.of(
                                "validation-service documentation validation",
                                "quality-service completeness check",
                                "audit-service accuracy validation",
                                "review-service documentation audit",
                                "metrics-service quality review",
                                "compliance-service doc completeness",
                                "standards-service validation checklist",
                                "governance-service documentation quality");
        }

        // Unit tests for specific functionality

        @Test
        void shouldProvideApiDocumentationGuidance() {
                // Given: An API documentation request
                AgentRequest request = AgentRequest.builder()
                                .type("documentation")
                                .description("API documentation synchronization property test")
                                .context(AgentContext.builder()
                                                .property("query", "api documentation synchronization")
                                                .build())
                                .securityContext(securityContext)
                                .build();

                // When: The Documentation Agent processes the request
                AgentResponse response = agentManager.processRequest(request);

                // Then: Should provide comprehensive API documentation guidance
                assertThat(response.isSuccess()).isTrue();
                String guidance = response.getOutput().toLowerCase();
                assertThat(guidance)
                                .contains("openapi")
                                .contains("synchronization");
        }

        @Test
        void shouldProvideValidationGuidance() {
                // Given: A documentation validation request
                AgentRequest request = AgentRequest.builder()
                                .type("documentation")
                                .description("Documentation completeness validation property test")
                                .context(AgentContext.builder()
                                                .property("query", "documentation completeness validation")
                                                .build())
                                .securityContext(securityContext)
                                .build();

                // When: The Documentation Agent processes the request
                AgentResponse response = agentManager.processRequest(request);

                // Then: Should provide validation guidance
                assertThat(response.isSuccess()).isTrue();
                String guidance = response.getOutput().toLowerCase();
                assertThat(guidance)
                                .contains("completeness")
                                .contains("validation")
                                .contains("audit");
        }

        @Test
        void shouldProvideTechnicalDocumentationGuidance() {
                // Given: A technical documentation request
                AgentRequest request = AgentRequest.builder()
                                .type("documentation")
                                .description("Documentation guidance performance property test")
                                .context(AgentContext.builder()
                                                .property("query", "technical documentation standards")
                                                .build())
                                .securityContext(securityContext).build();

                // When: The Documentation Agent processes the request
                AgentResponse response = agentManager.processRequest(request);

                // Then: Should provide technical documentation guidance
                assertThat(response.isSuccess()).isTrue();
                String guidance = response.getOutput().toLowerCase();
                assertThat(guidance)
                                .contains("readme")
                                .contains("technical")
                                .contains("documentation");
        }
}
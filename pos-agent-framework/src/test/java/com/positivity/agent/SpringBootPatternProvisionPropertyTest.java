package com.positivity.agent;

import com.positivity.agent.impl.ImplementationAgent;
import com.positivity.agent.registry.AgentRegistry;
import com.positivity.agent.registry.DefaultAgentRegistry;
import net.jqwik.api.*;
import org.junit.jupiter.api.BeforeEach;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Property-based test for Spring Boot pattern provision
 * **Feature: agent-structure, Property 5: Spring Boot pattern provision**
 * **Validates: Requirements REQ-002.1**
 */
class SpringBootPatternProvisionPropertyTest {

        private AgentRegistry registry;
        private ImplementationAgent implementationAgent;

        @BeforeEach
        void setUp() {
                registry = new DefaultAgentRegistry();
                implementationAgent = new ImplementationAgent();
                registry.registerAgent(implementationAgent);
        }

        /**
         * Property 5: Spring Boot pattern provision
         * For any microservice implementation request, the system should provide Spring
         * Boot development patterns
         */
        @Property(tries = 100)
        void springBootPatternProvision(
                        @ForAll("microserviceImplementationRequests") AgentConsultationRequest request) {
                // Given: An implementation agent capable of Spring Boot guidance
                implementationAgent = new ImplementationAgent();
                assertThat(implementationAgent.isAvailable())
                                .describedAs("Implementation agent should be available")
                                .isTrue();

                // When: Requesting guidance for microservice implementation
                AgentGuidanceResponse response = implementationAgent.provideGuidance(request).join();

                // Then: The response should be successful
                assertThat(response.isSuccessful())
                                .describedAs("Implementation agent should provide successful guidance")
                                .isTrue();

                // And: The guidance should contain Spring Boot patterns
                String guidance = response.guidance();
                assertThat(guidance.toLowerCase())
                                .describedAs("Guidance should contain Spring Boot patterns")
                                .containsAnyOf(
                                                "spring boot",
                                                "spring security",
                                                "spring data",
                                                "@controller",
                                                "@service",
                                                "@repository",
                                                "actuator",
                                                "starter dependencies",
                                                "layered architecture",
                                                "controller → service → repository",
                                                "spring boot 3",
                                                "microservice implementation");

                // And: The recommendations should include Spring Boot best practices
                List<String> recommendations = response.recommendations();
                assertThat(recommendations.stream().anyMatch(rec -> rec.toLowerCase().contains("spring boot") ||
                                rec.toLowerCase().contains("spring") ||
                                rec.toLowerCase().contains("layered architecture") ||
                                rec.toLowerCase().contains("dependency injection")))
                                .describedAs("Recommendations should include Spring Boot best practices")
                                .isTrue();

                // And: The confidence should be high for implementation guidance
                assertThat(response.confidence())
                                .describedAs("Implementation agent should have high confidence for Spring Boot patterns")
                                .isGreaterThan(0.8);
        }

        /**
         * Specific Spring Boot microservice patterns validation
         */
        @Property(tries = 100)
        void springBootMicroservicePatterns(@ForAll("springBootQueries") String query) {
                // Given: A Spring Boot specific implementation request
                implementationAgent = new ImplementationAgent();
                AgentConsultationRequest request = AgentConsultationRequest.create(
                                "implementation", query, Map.of("type", "microservice"));

                // When: Requesting Spring Boot guidance
                AgentGuidanceResponse response = implementationAgent.provideGuidance(request).join();

                // Then: The response should provide Spring Boot specific patterns
                assertThat(response.isSuccessful())
                                .describedAs("Should provide successful Spring Boot guidance")
                                .isTrue();

                String guidance = response.guidance();

                // Should contain core Spring Boot concepts
                assertThat(guidance.toLowerCase())
                                .describedAs("Should contain Spring Boot microservice patterns")
                                .containsAnyOf(
                                                "spring boot 3",
                                                "microservice",
                                                "controller",
                                                "service",
                                                "repository",
                                                "spring security",
                                                "spring data",
                                                "actuator",
                                                "health checks");

                // Should provide architectural guidance
                assertThat(guidance.toLowerCase())
                                .describedAs("Should provide architectural patterns")
                                .containsAnyOf(
                                                "layered architecture",
                                                "controller → service → repository",
                                                "dependency injection",
                                                "exception handling",
                                                "validation");
        }

        /**
         * Data access pattern provision for different data stores
         */
        @Property(tries = 100)
        void dataAccessPatternProvision(@ForAll("dataStoreTypes") String dataStoreType) {
                // Given: A data access implementation request for specific data store
                implementationAgent = new ImplementationAgent();
                AgentConsultationRequest request = AgentConsultationRequest.create(
                                "implementation",
                                "implement data access for " + dataStoreType,
                                Map.of("dataStore", dataStoreType));

                // When: Requesting data access guidance
                AgentGuidanceResponse response = implementationAgent.provideGuidance(request).join();

                // Then: The response should provide appropriate data access patterns
                assertThat(response.isSuccessful())
                                .describedAs("Should provide successful data access guidance")
                                .isTrue();

                String guidance = response.guidance();

                if (dataStoreType.toLowerCase().contains("dynamodb")) {
                        assertThat(guidance.toLowerCase())
                                        .describedAs("Should provide DynamoDB specific patterns")
                                        .containsAnyOf(
                                                        "spring data jpa",
                                                        "repository pattern",
                                                        "transaction",
                                                        "@transactional",
                                                        "connection pooling");
                }

                if (dataStoreType.toLowerCase().contains("elasticache")) {
                        assertThat(guidance.toLowerCase())
                                        .describedAs("Should provide ElastiCache specific patterns")
                                        .containsAnyOf(
                                                        "caching",
                                                        "spring cache",
                                                        "@cacheable",
                                                        "cache strategies");
                }
        }

        @Provide
        Arbitrary<AgentConsultationRequest> microserviceImplementationRequests() {
                return Arbitraries.of(
                                AgentConsultationRequest.create("implementation",
                                                "Spring Boot microservice for catalog management",
                                                Map.of()),
                                AgentConsultationRequest.create("implementation",
                                                "create Spring Boot service for customer management",
                                                Map.of()),
                                AgentConsultationRequest.create("implementation",
                                                "Spring Boot REST API for order processing", Map.of()),
                                AgentConsultationRequest.create("implementation",
                                                "microservice architecture for inventory management",
                                                Map.of()),
                                AgentConsultationRequest.create("implementation",
                                                "create microservice with Spring Boot patterns",
                                                Map.of()),
                                AgentConsultationRequest.create("implementation",
                                                "Spring Boot service layer for payment processing",
                                                Map.of()),
                                AgentConsultationRequest.create("implementation",
                                                "microservice implementation for product catalog",
                                                Map.of()),
                                AgentConsultationRequest.create("implementation",
                                                "create Spring Boot application for vehicle management", Map.of()));
        }

        @Provide
        Arbitrary<String> springBootQueries() {
                return Arbitraries.of(
                                "implement Spring Boot microservice",
                                "create Spring Boot application",
                                "develop microservice with Spring Boot",
                                "Spring Boot REST API implementation",
                                "microservice architecture with Spring Boot",
                                "Spring Boot service layer design",
                                "implement Spring Boot patterns",
                                "create Spring Boot web service");
        }

        @Provide
        Arbitrary<String> dataStoreTypes() {
                return Arbitraries.of(
                                "DynamoDB",
                                "ElastiCache",
                                "database",
                                "cache",
                                "relational database",
                                "NoSQL database");
        }
}
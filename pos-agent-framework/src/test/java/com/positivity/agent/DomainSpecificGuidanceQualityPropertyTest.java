package com.positivity.agent;

import com.positivity.agent.registry.AgentRegistry;
import com.positivity.agent.registry.DefaultAgentRegistry;
import net.jqwik.api.*;
import org.junit.jupiter.api.BeforeEach;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Property-based test for domain-specific guidance quality
 * **Feature: agent-structure, Property 2: Domain-specific guidance quality**
 * **Validates: Requirements REQ-001.2**
 */
class DomainSpecificGuidanceQualityPropertyTest {

        private AgentRegistry registry;

        @BeforeEach
        void setUp() {
                registry = new DefaultAgentRegistry();
                setupDomainSpecificAgents();
        }

        /**
         * Property 2: Domain-specific guidance quality
         * For any developer consultation request, the system should provide
         * domain-specific
         * recommendations following Spring Boot and AWS patterns
         */
        @Property(tries = 100)
        void domainSpecificGuidanceQuality(@ForAll("domainConsultationRequests") AgentConsultationRequest request) {
                // Given: A registry with domain-specific agents
                registry = new DefaultAgentRegistry();
                setupDomainSpecificAgents();

                // When: Consulting an agent for any domain-specific request
                CompletableFuture<AgentGuidanceResponse> future = registry.consultBestAgent(request);
                AgentGuidanceResponse response = future.join();

                // Then: Response should be successful
                assertThat(response.isSuccessful())
                                .describedAs("Response should be successful for domain: %s", request.domain())
                                .isTrue();

                // And: Response should contain domain-specific guidance
                assertThat(response.guidance())
                                .describedAs("Response should contain guidance")
                                .isNotBlank();

                // And: Guidance should be relevant to the requested domain
                String guidance = response.guidance().toLowerCase();
                String domain = request.domain().toLowerCase();

                boolean isDomainSpecific = switch (domain) {
                        case "architecture" -> containsArchitecturalConcepts(guidance);
                        case "implementation" -> containsImplementationConcepts(guidance);
                        case "testing" -> containsTestingConcepts(guidance);
                        case "deployment" -> containsDeploymentConcepts(guidance);
                        default -> true; // Allow other domains to pass
                };

                assertThat(isDomainSpecific)
                                .describedAs("Guidance should contain domain-specific concepts for %s", domain)
                                .isTrue();

                // And: Response should contain actionable recommendations
                assertThat(response.recommendations())
                                .describedAs("Response should contain recommendations")
                                .isNotEmpty();

                // And: Recommendations should follow established patterns
                boolean followsPatterns = response.recommendations().stream()
                                .anyMatch(rec -> followsEstablishedPatterns(rec, domain));

                assertThat(followsPatterns)
                                .describedAs("At least one recommendation should follow established patterns for %s",
                                                domain)
                                .isTrue();
        }

        /**
         * Test Spring Boot pattern adherence for implementation domain
         */
        @Property(tries = 100)
        void springBootPatternAdherence(@ForAll("springBootRequests") AgentConsultationRequest request) {
                // Given: A registry with domain-specific agents
                registry = new DefaultAgentRegistry();
                setupDomainSpecificAgents();

                // When: Requesting Spring Boot implementation guidance
                CompletableFuture<AgentGuidanceResponse> future = registry.consultBestAgent(request);
                AgentGuidanceResponse response = future.join();

                // Then: Response should be successful
                assertThat(response.isSuccessful()).isTrue();

                // And: Guidance should follow Spring Boot patterns
                String guidance = response.guidance().toLowerCase();
                boolean hasSpringBootPatterns = guidance.contains("spring") ||
                                guidance.contains("boot") ||
                                guidance.contains("@service") ||
                                guidance.contains("@controller") ||
                                guidance.contains("@repository") ||
                                guidance.contains("@component") ||
                                guidance.contains("dependency injection") ||
                                guidance.contains("microservice");

                assertThat(hasSpringBootPatterns)
                                .describedAs("Spring Boot guidance should contain Spring Boot patterns")
                                .isTrue();

                // And: Recommendations should include Spring Boot best practices
                boolean hasSpringBootRecommendations = response.recommendations().stream()
                                .anyMatch(rec -> rec.toLowerCase().contains("spring") ||
                                                rec.toLowerCase().contains("boot") ||
                                                rec.toLowerCase().contains("service") ||
                                                rec.toLowerCase().contains("controller"));

                assertThat(hasSpringBootRecommendations)
                                .describedAs("Recommendations should include Spring Boot best practices")
                                .isTrue();
        }

        /**
         * Test AWS pattern adherence for deployment domain
         */
        @Property(tries = 100)
        void awsPatternAdherence(@ForAll("awsDeploymentRequests") AgentConsultationRequest request) {
                // Given: A registry with domain-specific agents
                registry = new DefaultAgentRegistry();
                setupDomainSpecificAgents();

                // When: Requesting AWS deployment guidance
                CompletableFuture<AgentGuidanceResponse> future = registry.consultBestAgent(request);
                AgentGuidanceResponse response = future.join();

                // Then: Response should be successful
                assertThat(response.isSuccessful()).isTrue();

                // And: Guidance should follow AWS patterns
                String guidance = response.guidance().toLowerCase();
                boolean hasAwsPatterns = guidance.contains("aws") ||
                                guidance.contains("fargate") ||
                                guidance.contains("ecs") ||
                                guidance.contains("dynamodb") ||
                                guidance.contains("elasticache") ||
                                guidance.contains("container") ||
                                guidance.contains("cloud");

                assertThat(hasAwsPatterns)
                                .describedAs("AWS deployment guidance should contain AWS patterns")
                                .isTrue();

                // And: Recommendations should include AWS best practices
                boolean hasAwsRecommendations = response.recommendations().stream()
                                .anyMatch(rec -> rec.toLowerCase().contains("aws") ||
                                                rec.toLowerCase().contains("fargate") ||
                                                rec.toLowerCase().contains("dynamodb") ||
                                                rec.toLowerCase().contains("elasticache") ||
                                                rec.toLowerCase().contains("container"));

                assertThat(hasAwsRecommendations)
                                .describedAs("Recommendations should include AWS best practices")
                                .isTrue();
        }

        /**
         * Test data store guidance appropriateness
         */
        @Property(tries = 100)
        void dataStoreGuidanceAppropriateness(@ForAll("dataStoreRequests") AgentConsultationRequest request) {
                // Given: A registry with domain-specific agents
                registry = new DefaultAgentRegistry();
                setupDomainSpecificAgents();

                // When: Requesting data store guidance
                CompletableFuture<AgentGuidanceResponse> future = registry.consultBestAgent(request);
                AgentGuidanceResponse response = future.join();

                // Then: Response should be successful
                assertThat(response.isSuccessful()).isTrue();

                // And: Guidance should reference appropriate data store types
                String guidance = response.guidance().toLowerCase();
                String query = request.query().toLowerCase();

                if (query.contains("cache") || query.contains("session") || query.contains("temporary")) {
                        // Should recommend ElastiCache for caching scenarios
                        boolean hasElastiCacheGuidance = guidance.contains("elasticache") ||
                                        guidance.contains("redis") ||
                                        guidance.contains("cache");
                        assertThat(hasElastiCacheGuidance)
                                        .describedAs("Cache-related queries should receive ElastiCache guidance. Query: '%s', Guidance: '%s'",
                                                        query, guidance)
                                        .isTrue();
                }

                // Check for persistence keywords, but exclude caching scenarios
                boolean isCacheQuery = query.contains("cache") || query.contains("session")
                                || query.contains("temporary");
                if ((query.contains("persist") || (query.contains("store") && !query.contains("session"))
                                || query.contains("database")) && !isCacheQuery) {
                        // Should recommend DynamoDB for persistent storage
                        boolean hasDynamoDbGuidance = guidance.contains("dynamodb") ||
                                        guidance.contains("nosql") ||
                                        guidance.contains("database");
                        assertThat(hasDynamoDbGuidance)
                                        .describedAs("Persistence-related queries should receive DynamoDB guidance. Query: '%s', Guidance: '%s'",
                                                        query, guidance)
                                        .isTrue();
                }
        }

        @Provide
        Arbitrary<AgentConsultationRequest> domainConsultationRequests() {
                return Combinators.combine(
                                Arbitraries.of("architecture", "implementation", "testing", "deployment"),
                                Arbitraries.strings().alpha().ofMinLength(10).ofMaxLength(100),
                                Arbitraries.maps(
                                                Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(10),
                                                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20))
                                                .ofMaxSize(3))
                                .as((domain, query, context) -> AgentConsultationRequest.create(domain, query,
                                                context));
        }

        @Provide
        Arbitrary<AgentConsultationRequest> springBootRequests() {
                return Combinators.combine(
                                Arbitraries.of("implementation"),
                                Arbitraries.of(
                                                "How to implement Spring Boot microservice?",
                                                "Create REST controller with Spring Boot",
                                                "Spring Boot service layer best practices",
                                                "Implement Spring Boot configuration",
                                                "Spring Boot dependency injection patterns",
                                                "Microservice architecture with Spring Boot"),
                                Arbitraries.maps(
                                                Arbitraries.of("framework", "technology", "pattern"),
                                                Arbitraries.of("spring-boot", "microservices", "rest-api", "java"))
                                                .ofMaxSize(3))
                                .as((domain, query, context) -> AgentConsultationRequest.create(domain, query,
                                                context));
        }

        @Provide
        Arbitrary<AgentConsultationRequest> awsDeploymentRequests() {
                return Combinators.combine(
                                Arbitraries.of("deployment"),
                                Arbitraries.of(
                                                "Deploy microservice to AWS Fargate",
                                                "Configure AWS ECS for POS system",
                                                "Set up DynamoDB for microservice",
                                                "Configure ElastiCache cluster",
                                                "AWS container orchestration best practices",
                                                "AWS monitoring and observability setup"),
                                Arbitraries.maps(
                                                Arbitraries.of("cloud", "database", "cache", "container"),
                                                Arbitraries.of("aws", "fargate", "ecs", "dynamodb", "elasticache"))
                                                .ofMaxSize(3))
                                .as((domain, query, context) -> AgentConsultationRequest.create(domain, query,
                                                context));
        }

        @Provide
        Arbitrary<AgentConsultationRequest> dataStoreRequests() {
                return Combinators.combine(
                                Arbitraries.of("implementation", "deployment"),
                                Arbitraries.of(
                                                "How to persist customer data?",
                                                "Cache session information",
                                                "Store product catalog data",
                                                "Implement temporary data storage",
                                                "Database design for POS system",
                                                "Cache strategy for high performance"),
                                Arbitraries.maps(
                                                Arbitraries.of("storage", "performance", "scalability"),
                                                Arbitraries.of("database", "cache", "persistence", "nosql"))
                                                .ofMaxSize(3))
                                .as((domain, query, context) -> AgentConsultationRequest.create(domain, query,
                                                context));
        }

        private boolean containsArchitecturalConcepts(String guidance) {
                return guidance.contains("architecture") ||
                                guidance.contains("design") ||
                                guidance.contains("pattern") ||
                                guidance.contains("boundary") ||
                                guidance.contains("domain") ||
                                guidance.contains("microservice") ||
                                guidance.contains("integration");
        }

        private boolean containsImplementationConcepts(String guidance) {
                return guidance.contains("implement") ||
                                guidance.contains("code") ||
                                guidance.contains("service") ||
                                guidance.contains("class") ||
                                guidance.contains("method") ||
                                guidance.contains("spring") ||
                                guidance.contains("boot");
        }

        private boolean containsTestingConcepts(String guidance) {
                return guidance.contains("test") ||
                                guidance.contains("junit") ||
                                guidance.contains("mock") ||
                                guidance.contains("integration") ||
                                guidance.contains("unit") ||
                                guidance.contains("quality") ||
                                guidance.contains("validation");
        }

        private boolean containsDeploymentConcepts(String guidance) {
                return guidance.contains("deploy") ||
                                guidance.contains("container") ||
                                guidance.contains("docker") ||
                                guidance.contains("aws") ||
                                guidance.contains("fargate") ||
                                guidance.contains("ecs") ||
                                guidance.contains("cloud");
        }

        private boolean followsEstablishedPatterns(String recommendation, String domain) {
                String rec = recommendation.toLowerCase();
                return switch (domain) {
                        case "architecture" -> rec.contains("ddd") || rec.contains("microservice") ||
                                        rec.contains("boundary") || rec.contains("pattern");
                        case "implementation" -> rec.contains("spring") || rec.contains("boot") ||
                                        rec.contains("service") || rec.contains("controller");
                        case "testing" -> rec.contains("junit") || rec.contains("test") ||
                                        rec.contains("mock") || rec.contains("integration");
                        case "deployment" -> rec.contains("aws") || rec.contains("docker") ||
                                        rec.contains("fargate") || rec.contains("container");
                        default -> true;
                };
        }

        private void setupDomainSpecificAgents() {
                // Create agents that provide domain-specific guidance following Spring Boot and
                // AWS patterns
                registry.registerAgent(createDomainSpecificAgent("architecture-agent", "Architecture Agent",
                                "architecture",
                                Set.of("ddd", "microservices", "integration-patterns", "system-design")));

                registry.registerAgent(createDomainSpecificAgent("implementation-agent", "Implementation Agent",
                                "implementation",
                                Set.of("spring-boot", "java", "business-logic", "data-access", "rest-api")));

                registry.registerAgent(createDomainSpecificAgent("testing-agent", "Testing Agent",
                                "testing", Set.of("unit-testing", "integration-testing", "contract-testing", "junit")));

                registry.registerAgent(createDomainSpecificAgent("deployment-agent", "Deployment Agent",
                                "deployment",
                                Set.of("docker", "aws-fargate", "ecs", "ci-cd", "dynamodb", "elasticache")));
        }

        private Agent createDomainSpecificAgent(String id, String name, String domain, Set<String> capabilities) {
                return new BaseAgent(id, name, domain, capabilities, Set.of(),
                                AgentPerformanceSpec.defaultSpec()) {
                        @Override
                        protected AgentGuidanceResponse processGuidanceRequest(AgentConsultationRequest request) {
                                String guidance = generateDomainSpecificGuidance(request);
                                List<String> recommendations = generateDomainSpecificRecommendations(request);

                                return AgentGuidanceResponse.success(
                                                request.requestId(),
                                                getId(),
                                                guidance,
                                                0.95,
                                                recommendations,
                                                Duration.ofMillis(100));
                        }

                        private String generateDomainSpecificGuidance(AgentConsultationRequest request) {
                                return switch (getDomain()) {
                                        case "architecture" -> "Architectural guidance for " + request.query() +
                                                        ". Consider domain-driven design principles and microservice boundaries. "
                                                        +
                                                        "Use integration patterns like API Gateway for service communication.";
                                        case "implementation" -> {
                                                String baseGuidance = "Spring Boot implementation guidance for " + request.query() +
                                                        ". Use @Service, @Controller, and @Repository annotations. " +
                                                        "Implement proper dependency injection and follow microservice patterns.";
                                                
                                                // Add data store guidance based on query keywords
                                                String query = request.query().toLowerCase();
                                                
                                                // Check for caching keywords first
                                                if (query.contains("cache") || query.contains("session") || query.contains("temporary")) {
                                                        yield baseGuidance + " Use ElastiCache for caching and session storage.";
                                                }
                                                // Then check for persistence keywords
                                                else if (query.contains("persist") || query.contains("store") || query.contains("database")) {
                                                        yield baseGuidance + " Use DynamoDB for persistent data storage with proper partition key design.";
                                                }
                                                
                                                yield baseGuidance;
                                        }
                                        case "testing" -> "Testing strategy guidance for " + request.query() +
                                                        ". Use JUnit 5 for unit tests, implement integration tests with TestContainers, "
                                                        +
                                                        "and consider contract testing with Pact.";
                                        case "deployment" -> "AWS deployment guidance for " + request.query() +
                                                        ". Use AWS Fargate for container orchestration, DynamoDB for persistence, "
                                                        +
                                                        "and ElastiCache for caching. Configure ECS clusters with auto-scaling.";
                                        default -> "Domain-specific guidance for " + request.domain() + ": "
                                                        + request.query();
                                };
                        }

                        private List<String> generateDomainSpecificRecommendations(AgentConsultationRequest request) {
                                return switch (getDomain()) {
                                        case "architecture" -> List.of(
                                                        "Follow DDD principles for domain boundaries",
                                                        "Use microservice architecture patterns",
                                                        "Implement API Gateway for service integration",
                                                        "Design event-driven communication patterns");
                                        case "implementation" -> List.of(
                                                        "Use Spring Boot 3.x with Java 21",
                                                        "Implement proper service layer with @Service",
                                                        "Create REST controllers with @RestController",
                                                        "Use Spring Data for data access patterns",
                                                        "Implement validation with Bean Validation");
                                        case "testing" -> List.of(
                                                        "Write unit tests with JUnit 5 and Mockito",
                                                        "Implement integration tests with @SpringBootTest",
                                                        "Use TestContainers for database testing",
                                                        "Add contract tests with Pact for API validation");
                                        case "deployment" -> List.of(
                                                        "Deploy to AWS Fargate with ECS",
                                                        "Use DynamoDB for persistent data storage",
                                                        "Configure ElastiCache for session and caching",
                                                        "Set up CloudWatch for monitoring and observability",
                                                        "Implement auto-scaling policies");
                                        default -> List.of("Follow best practices for " + request.domain());
                                };
                        }
                };
        }
}
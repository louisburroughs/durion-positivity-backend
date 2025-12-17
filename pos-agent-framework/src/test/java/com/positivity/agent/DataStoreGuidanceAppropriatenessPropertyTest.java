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
 * Property-based test for data store guidance appropriateness
 * **Feature: agent-structure, Property 4: Data store guidance appropriateness**
 * **Validates: Requirements REQ-001.4**
 */
class DataStoreGuidanceAppropriatenessPropertyTest {

    private AgentRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultAgentRegistry();
        setupDataStoreAwareAgents();
    }

    /**
     * Property 4: Data store guidance appropriateness
     * For any microservice guidance request, the system should reference
     * appropriate
     * data store types (DynamoDB vs ElastiCache) and AWS services
     */
    @Property(tries = 100)
    void dataStoreGuidanceAppropriateness(@ForAll("microserviceGuidanceRequests") AgentConsultationRequest request) {
        // Given: A registry with data store aware agents
        registry = new DefaultAgentRegistry();
        setupDataStoreAwareAgents();

        // When: Requesting microservice guidance
        CompletableFuture<AgentGuidanceResponse> future = registry.consultBestAgent(request);
        AgentGuidanceResponse response = future.join();

        // Then: Response should be successful
        assertThat(response.isSuccessful())
                .describedAs("Response should be successful for microservice guidance")
                .isTrue();

        // And: Response should reference appropriate data store types
        String guidance = response.guidance().toLowerCase();
        String query = request.query().toLowerCase();

        // Verify appropriate data store recommendations based on use case
        if (containsCachingKeywords(query)) {
            // Should recommend ElastiCache for caching scenarios
            boolean hasElastiCacheGuidance = guidance.contains("elasticache") ||
                    guidance.contains("redis") ||
                    guidance.contains("cache") ||
                    guidance.contains("in-memory");

            assertThat(hasElastiCacheGuidance)
                    .describedAs("Cache-related queries should receive ElastiCache guidance")
                    .isTrue();
        }

        if (containsPersistenceKeywords(query)) {
            // Should recommend DynamoDB for persistent storage
            boolean hasDynamoDbGuidance = guidance.contains("dynamodb") ||
                    guidance.contains("nosql") ||
                    guidance.contains("database") ||
                    guidance.contains("persistent");

            assertThat(hasDynamoDbGuidance)
                    .describedAs(
                            "Persistence-related queries should receive DynamoDB guidance. Query: '%s', Guidance: '%s'",
                            query, guidance)
                    .isTrue();
        }

        // And: Response should reference AWS services
        boolean hasAwsServiceReference = guidance.contains("aws") ||
                guidance.contains("dynamodb") ||
                guidance.contains("elasticache") ||
                guidance.contains("fargate") ||
                guidance.contains("ecs");

        assertThat(hasAwsServiceReference)
                .describedAs("Microservice guidance should reference AWS services")
                .isTrue();
    }

    /**
     * Test specific DynamoDB guidance scenarios
     */
    @Property(tries = 100)
    void dynamoDbGuidanceScenarios(@ForAll("dynamoDbRequests") AgentConsultationRequest request) {
        // Given: A registry with data store aware agents
        registry = new DefaultAgentRegistry();
        setupDataStoreAwareAgents();

        // When: Requesting DynamoDB-specific guidance
        CompletableFuture<AgentGuidanceResponse> future = registry.consultBestAgent(request);
        AgentGuidanceResponse response = future.join();

        // Then: Response should be successful
        assertThat(response.isSuccessful()).isTrue();

        // And: Response should contain DynamoDB-specific guidance
        String guidance = response.guidance().toLowerCase();
        boolean hasDynamoDbGuidance = guidance.contains("dynamodb") ||
                guidance.contains("nosql") ||
                guidance.contains("partition key") ||
                guidance.contains("sort key") ||
                guidance.contains("gsi") ||
                guidance.contains("table");

        assertThat(hasDynamoDbGuidance)
                .describedAs("DynamoDB requests should receive DynamoDB-specific guidance")
                .isTrue();

        // And: Recommendations should include DynamoDB best practices
        boolean hasDynamoDbRecommendations = response.recommendations().stream()
                .anyMatch(rec -> rec.toLowerCase().contains("dynamodb") ||
                        rec.toLowerCase().contains("partition") ||
                        rec.toLowerCase().contains("nosql") ||
                        rec.toLowerCase().contains("table"));

        assertThat(hasDynamoDbRecommendations)
                .describedAs("DynamoDB requests should include DynamoDB recommendations")
                .isTrue();
    }

    /**
     * Test specific ElastiCache guidance scenarios
     */
    @Property(tries = 100)
    void elastiCacheGuidanceScenarios(@ForAll("elastiCacheRequests") AgentConsultationRequest request) {
        // Given: A registry with data store aware agents
        registry = new DefaultAgentRegistry();
        setupDataStoreAwareAgents();

        // When: Requesting ElastiCache-specific guidance
        CompletableFuture<AgentGuidanceResponse> future = registry.consultBestAgent(request);
        AgentGuidanceResponse response = future.join();

        // Then: Response should be successful
        assertThat(response.isSuccessful()).isTrue();

        // And: Response should contain ElastiCache-specific guidance
        String guidance = response.guidance().toLowerCase();
        boolean hasElastiCacheGuidance = guidance.contains("elasticache") ||
                guidance.contains("redis") ||
                guidance.contains("cache") ||
                guidance.contains("in-memory") ||
                guidance.contains("session") ||
                guidance.contains("ttl");

        assertThat(hasElastiCacheGuidance)
                .describedAs("ElastiCache requests should receive ElastiCache-specific guidance")
                .isTrue();

        // And: Recommendations should include ElastiCache best practices
        boolean hasElastiCacheRecommendations = response.recommendations().stream()
                .anyMatch(rec -> rec.toLowerCase().contains("cache") ||
                        rec.toLowerCase().contains("redis") ||
                        rec.toLowerCase().contains("elasticache") ||
                        rec.toLowerCase().contains("session"));

        assertThat(hasElastiCacheRecommendations)
                .describedAs("ElastiCache requests should include caching recommendations")
                .isTrue();
    }

    /**
     * Test data store selection guidance based on service type
     */
    @Property(tries = 100)
    void dataStoreSelectionByServiceType(@ForAll("serviceTypeRequests") AgentConsultationRequest request) {
        // Given: A registry with data store aware agents
        registry = new DefaultAgentRegistry();
        setupDataStoreAwareAgents();

        // When: Requesting guidance for specific service types
        CompletableFuture<AgentGuidanceResponse> future = registry.consultBestAgent(request);
        AgentGuidanceResponse response = future.join();

        // Then: Response should be successful
        assertThat(response.isSuccessful()).isTrue();

        // And: Data store recommendations should match service characteristics
        String query = request.query().toLowerCase();
        String guidance = response.guidance().toLowerCase();

        if (query.contains("vehicle-reference") || query.contains("reference-data")) {
            // Vehicle reference services should use ElastiCache
            boolean hasElastiCacheGuidance = guidance.contains("elasticache") ||
                    guidance.contains("cache") ||
                    guidance.contains("redis");

            assertThat(hasElastiCacheGuidance)
                    .describedAs("Vehicle reference services should recommend ElastiCache")
                    .isTrue();
        }

        if (query.contains("customer") || query.contains("order") || query.contains("catalog")) {
            // Core business services should use DynamoDB
            boolean hasDynamoDbGuidance = guidance.contains("dynamodb") ||
                    guidance.contains("nosql") ||
                    guidance.contains("database");

            assertThat(hasDynamoDbGuidance)
                    .describedAs("Core business services should recommend DynamoDB")
                    .isTrue();
        }
    }

    @Provide
    Arbitrary<AgentConsultationRequest> microserviceGuidanceRequests() {
        return Combinators.combine(
                Arbitraries.of("implementation", "deployment", "architecture"),
                Arbitraries.of(
                        "How to implement microservice data access?",
                        "Design data storage for POS system",
                        "Choose database for customer service",
                        "Implement caching for performance",
                        "Store session data efficiently",
                        "Persist product catalog data",
                        "Cache vehicle reference data",
                        "Design data layer for orders",
                        "Implement temporary data storage",
                        "Choose storage for high-volume reads"),
                Arbitraries.maps(
                        Arbitraries.of("service", "data", "performance", "scalability"),
                        Arbitraries.of("microservice", "pos", "aws", "storage", "database", "cache")).ofMaxSize(3))
                .as((domain, query, context) -> AgentConsultationRequest.create(domain, query, context));
    }

    @Provide
    Arbitrary<AgentConsultationRequest> dynamoDbRequests() {
        return Combinators.combine(
                Arbitraries.of("implementation", "deployment"),
                Arbitraries.of(
                        "Design DynamoDB table for customers",
                        "Implement DynamoDB access patterns",
                        "Store order data in DynamoDB",
                        "Design partition keys for catalog",
                        "Implement DynamoDB queries",
                        "Persist user profiles in NoSQL",
                        "Design database schema for POS",
                        "Store transactional data permanently"),
                Arbitraries.maps(
                        Arbitraries.of("database", "persistence", "storage"),
                        Arbitraries.of("dynamodb", "nosql", "aws", "permanent", "transactional")).ofMaxSize(3))
                .as((domain, query, context) -> AgentConsultationRequest.create(domain, query, context));
    }

    @Provide
    Arbitrary<AgentConsultationRequest> elastiCacheRequests() {
        return Combinators.combine(
                Arbitraries.of("implementation", "deployment"),
                Arbitraries.of(
                        "Implement Redis caching strategy",
                        "Cache session data with ElastiCache",
                        "Store temporary data in cache",
                        "Implement in-memory storage",
                        "Cache vehicle reference data",
                        "Store user sessions efficiently",
                        "Implement fast data retrieval",
                        "Cache frequently accessed data"),
                Arbitraries.maps(
                        Arbitraries.of("cache", "performance", "session"),
                        Arbitraries.of("elasticache", "redis", "temporary", "fast", "memory")).ofMaxSize(3))
                .as((domain, query, context) -> AgentConsultationRequest.create(domain, query, context));
    }

    @Provide
    Arbitrary<AgentConsultationRequest> serviceTypeRequests() {
        return Combinators.combine(
                Arbitraries.of("implementation", "architecture"),
                Arbitraries.of(
                        "Design data storage for pos-customer service",
                        "Implement pos-vehicle-reference-nhtsa caching",
                        "Store data for pos-catalog service",
                        "Design pos-order data persistence",
                        "Implement pos-vehicle-reference-carapi storage",
                        "Cache data for pos-vehicle-fitment service",
                        "Store pos-inventory data efficiently",
                        "Design reference data caching strategy"),
                Arbitraries.maps(
                        Arbitraries.of("service-type", "data-pattern"),
                        Arbitraries.of("business-service", "reference-service", "core-service")).ofMaxSize(2))
                .as((domain, query, context) -> AgentConsultationRequest.create(domain, query, context));
    }

    private boolean containsCachingKeywords(String query) {
        return query.contains("cache") ||
                query.contains("session") ||
                query.contains("temporary") ||
                query.contains("fast") ||
                query.contains("memory") ||
                query.contains("redis") ||
                query.contains("elasticache") ||
                query.contains("reference");
    }

    private boolean containsPersistenceKeywords(String query) {
        // Exclude session/cache-related storage from persistence
        if (containsCachingKeywords(query)) {
            return false;
        }

        return query.contains("persist") ||
                (query.contains("store") && !query.contains("session")) ||
                query.contains("database") ||
                query.contains("permanent") ||
                query.contains("save") ||
                query.contains("customer") ||
                query.contains("order") ||
                query.contains("catalog") ||
                query.contains("transactional");
    }

    private void setupDataStoreAwareAgents() {
        // Create agents that provide appropriate data store guidance
        registry.registerAgent(createDataStoreAwareAgent("implementation-agent", "Implementation Agent",
                "implementation", Set.of("spring-boot", "data-access", "dynamodb", "elasticache")));

        registry.registerAgent(createDataStoreAwareAgent("deployment-agent", "Deployment Agent",
                "deployment", Set.of("aws", "dynamodb", "elasticache", "fargate", "ecs")));

        registry.registerAgent(createDataStoreAwareAgent("architecture-agent", "Architecture Agent",
                "architecture", Set.of("data-architecture", "storage-patterns", "aws-services")));
    }

    private Agent createDataStoreAwareAgent(String id, String name, String domain, Set<String> capabilities) {
        return new BaseAgent(id, name, domain, capabilities, Set.of(),
                AgentPerformanceSpec.defaultSpec()) {
            @Override
            protected AgentGuidanceResponse processGuidanceRequest(AgentConsultationRequest request) {
                String guidance = generateDataStoreGuidance(request);
                List<String> recommendations = generateDataStoreRecommendations(request);

                return AgentGuidanceResponse.success(
                        request.requestId(),
                        getId(),
                        guidance,
                        0.95,
                        recommendations,
                        Duration.ofMillis(100));
            }

            private String generateDataStoreGuidance(AgentConsultationRequest request) {
                String query = request.query().toLowerCase();
                String baseGuidance = "For " + request.domain() + " requirements: " + request.query();

                if (containsCachingKeywords(query)) {
                    return baseGuidance
                            + " - Use ElastiCache (Redis) for caching, session storage, and temporary data. " +
                            "Configure appropriate TTL and memory optimization for high-performance access.";
                }

                if (containsPersistenceKeywords(query)) {
                    return baseGuidance + " - Use DynamoDB for persistent storage with proper partition key design. " +
                            "Implement appropriate access patterns and consider GSI for query flexibility.";
                }

                // Default guidance includes both options
                return baseGuidance + " - Choose DynamoDB for persistent data storage and ElastiCache for caching. " +
                        "AWS Fargate provides the container runtime for both data store integrations.";
            }

            private List<String> generateDataStoreRecommendations(AgentConsultationRequest request) {
                String query = request.query().toLowerCase();

                if (containsCachingKeywords(query)) {
                    return List.of(
                            "Use ElastiCache (Redis) for caching and session storage",
                            "Configure appropriate TTL for cached data",
                            "Implement cache-aside pattern for data access",
                            "Monitor cache hit rates and memory usage");
                }

                if (containsPersistenceKeywords(query)) {
                    return List.of(
                            "Use DynamoDB for persistent NoSQL storage",
                            "Design proper partition keys for even distribution",
                            "Consider Global Secondary Indexes (GSI) for queries",
                            "Implement proper error handling and retry logic");
                }

                // Default recommendations for mixed scenarios
                return List.of(
                        "Use DynamoDB for persistent data storage",
                        "Use ElastiCache for caching and temporary data",
                        "Deploy on AWS Fargate with proper IAM roles",
                        "Implement monitoring for both data stores");
            }
        };
    }
}
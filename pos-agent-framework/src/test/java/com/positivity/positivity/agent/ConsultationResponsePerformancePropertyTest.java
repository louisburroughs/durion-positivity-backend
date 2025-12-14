package com.positivity.positivity.agent;

import com.positivity.positivity.agent.registry.AgentRegistry;
import com.positivity.positivity.agent.registry.DefaultAgentRegistry;
import net.jqwik.api.*;
import net.jqwik.api.constraints.NotEmpty;
import org.junit.jupiter.api.BeforeEach;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Property-based test for consultation response performance
 * **Feature: agent-structure, Property 2: Consultation response performance**
 * **Validates: Requirements REQ-001.2**
 */
class ConsultationResponsePerformancePropertyTest {
    
    private AgentRegistry registry;
    
    @BeforeEach
    void setUp() {
        registry = new DefaultAgentRegistry();
        setupPerformanceTestAgents();
    }
    
    /**
     * Property 2: Consultation response performance
     * For any developer consultation request, the system should provide domain-specific 
     * recommendations within 2 seconds with 95% accuracy following established Spring Boot and AWS patterns
     */
    @Property(tries = 100)
    void consultationResponsePerformance(
            @ForAll("validConsultationRequests") AgentConsultationRequest request) {
        
        // When: Consulting the best agent for any valid request
        long startTime = System.nanoTime();
        CompletableFuture<AgentGuidanceResponse> future = registry.consultBestAgent(request);
        AgentGuidanceResponse response = future.join();
        long endTime = System.nanoTime();
        Duration responseTime = Duration.ofNanos(endTime - startTime);
        
        // Then: Response time should be within 2 seconds
        Assertions.assertThat(responseTime)
                .as("Response time should be within 2 seconds")
                .isLessThanOrEqualTo(Duration.ofSeconds(2));
        
        // And: Response should be successful
        Assertions.assertThat(response.isSuccessful())
                .as("Response should be successful")
                .isTrue();
        
        // And: Response should have high accuracy (>= 95%)
        Assertions.assertThat(response.confidence())
                .as("Response confidence should be >= 95%")
                .isGreaterThanOrEqualTo(0.95);
        
        // And: Response should contain domain-specific guidance
        Assertions.assertThat(response.guidance())
                .as("Response should contain guidance")
                .isNotEmpty();
        
        // And: Response should contain recommendations
        Assertions.assertThat(response.recommendations())
                .as("Response should contain recommendations")
                .isNotEmpty();
    }
    
    @Property(tries = 100)
    void springBootPatternAccuracy(
            @ForAll("springBootRequests") AgentConsultationRequest request) {
        
        // When: Requesting Spring Boot guidance
        CompletableFuture<AgentGuidanceResponse> future = registry.consultBestAgent(request);
        AgentGuidanceResponse response = future.join();
        
        // Then: Response should follow Spring Boot patterns
        Assertions.assertThat(response.isSuccessful()).isTrue();
        Assertions.assertThat(response.confidence()).isGreaterThanOrEqualTo(0.96); // 96% for Spring Boot
        
        // And: Guidance should mention Spring Boot concepts
        String guidance = response.guidance().toLowerCase();
        boolean hasSpringBootConcepts = guidance.contains("spring") || 
                                       guidance.contains("boot") ||
                                       guidance.contains("microservice") ||
                                       guidance.contains("service");
        
        Assertions.assertThat(hasSpringBootConcepts)
                .as("Spring Boot guidance should contain relevant concepts")
                .isTrue();
    }
    
    @Property(tries = 100)
    void awsPatternsAccuracy(
            @ForAll("awsRequests") AgentConsultationRequest request) {
        
        // When: Requesting AWS guidance
        CompletableFuture<AgentGuidanceResponse> future = registry.consultBestAgent(request);
        AgentGuidanceResponse response = future.join();
        
        // Then: Response should follow AWS patterns
        Assertions.assertThat(response.isSuccessful()).isTrue();
        Assertions.assertThat(response.confidence()).isGreaterThanOrEqualTo(0.95);
        
        // And: Guidance should mention AWS concepts
        String guidance = response.guidance().toLowerCase();
        boolean hasAwsConcepts = guidance.contains("aws") || 
                               guidance.contains("fargate") ||
                               guidance.contains("dynamodb") ||
                               guidance.contains("elasticache") ||
                               guidance.contains("cloud");
        
        Assertions.assertThat(hasAwsConcepts)
                .as("AWS guidance should contain relevant concepts")
                .isTrue();
    }
    
    @Property(tries = 50)
    void concurrentConsultationPerformance(
            @ForAll("consultationRequestList") List<AgentConsultationRequest> requests) {
        
        Assume.that(requests.size() >= 2 && requests.size() <= 10);
        
        // When: Processing multiple concurrent consultation requests
        long startTime = System.nanoTime();
        List<CompletableFuture<AgentGuidanceResponse>> futures = requests.stream()
                .map(registry::consultBestAgent)
                .toList();
        
        List<AgentGuidanceResponse> responses = futures.stream()
                .map(CompletableFuture::join)
                .toList();
        long endTime = System.nanoTime();
        Duration totalTime = Duration.ofNanos(endTime - startTime);
        
        // Then: All requests should complete within reasonable time
        // Allow more time for concurrent requests but still maintain performance
        Duration maxExpectedTime = Duration.ofSeconds(2).multipliedBy(requests.size()).dividedBy(2);
        Assertions.assertThat(totalTime)
                .as("Concurrent requests should complete efficiently")
                .isLessThanOrEqualTo(maxExpectedTime);
        
        // And: All responses should be successful
        Assertions.assertThat(responses)
                .allMatch(AgentGuidanceResponse::isSuccessful, "All responses should be successful");
        
        // And: All responses should meet accuracy threshold
        Assertions.assertThat(responses)
                .allMatch(r -> r.confidence() >= 0.95, "All responses should meet accuracy threshold");
    }
    
    @Provide
    Arbitrary<AgentConsultationRequest> validConsultationRequests() {
        return Combinators.combine(
                Arbitraries.of("implementation", "architecture", "testing", "deployment", "security"),
                Arbitraries.strings().alpha().ofMinLength(10).ofMaxLength(100),
                Arbitraries.maps(
                        Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(10),
                        Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20)
                ).ofMaxSize(5)
        ).as((domain, query, context) -> 
                AgentConsultationRequest.create(domain, query, context));
    }
    
    @Provide
    Arbitrary<AgentConsultationRequest> springBootRequests() {
        return Combinators.combine(
                Arbitraries.of("implementation"),
                Arbitraries.of(
                        "How to implement Spring Boot service?",
                        "Create microservice with Spring Boot",
                        "Spring Boot configuration best practices",
                        "Implement REST controller in Spring Boot"
                ),
                Arbitraries.maps(
                        Arbitraries.of("framework", "technology", "pattern"),
                        Arbitraries.of("spring-boot", "microservices", "rest-api")
                ).ofMaxSize(3)
        ).as((domain, query, context) -> 
                AgentConsultationRequest.create(domain, query, context));
    }
    
    @Provide
    Arbitrary<AgentConsultationRequest> awsRequests() {
        return Combinators.combine(
                Arbitraries.of("deployment", "infrastructure"),
                Arbitraries.of(
                        "Deploy to AWS Fargate",
                        "Configure DynamoDB for microservice",
                        "Set up ElastiCache cluster",
                        "AWS best practices for POS system"
                ),
                Arbitraries.maps(
                        Arbitraries.of("cloud", "database", "cache"),
                        Arbitraries.of("aws", "dynamodb", "elasticache", "fargate")
                ).ofMaxSize(3)
        ).as((domain, query, context) -> 
                AgentConsultationRequest.create(domain, query, context));
    }
    
    @Provide
    Arbitrary<List<AgentConsultationRequest>> consultationRequestList() {
        return validConsultationRequests().list().ofMinSize(2).ofMaxSize(10);
    }
    
    private void setupPerformanceTestAgents() {
        // Create high-performance test agents
        registry.registerAgent(createHighPerformanceAgent("spring-boot-1", "Spring Boot Developer", 
                "implementation", Set.of("spring-boot", "microservices", "rest-api")));
        
        registry.registerAgent(createHighPerformanceAgent("aws-1", "AWS DevOps Agent", 
                "deployment", Set.of("aws", "fargate", "dynamodb", "elasticache")));
        
        registry.registerAgent(createHighPerformanceAgent("arch-1", "Architecture Agent", 
                "architecture", Set.of("ddd", "patterns", "microservices")));
        
        registry.registerAgent(createHighPerformanceAgent("test-1", "Testing Agent", 
                "testing", Set.of("unit-testing", "integration-testing")));
        
        registry.registerAgent(createHighPerformanceAgent("sec-1", "Security Agent", 
                "security", Set.of("jwt", "spring-security", "owasp")));
    }
    
    private Agent createHighPerformanceAgent(String id, String name, String domain, Set<String> capabilities) {
        return new BaseAgent(id, name, domain, capabilities, Set.of(), 
                AgentPerformanceSpec.highPerformanceSpec()) {
            @Override
            protected AgentGuidanceResponse processGuidanceRequest(AgentConsultationRequest request) {
                // Simulate processing time within performance bounds
                try {
                    Thread.sleep(50); // 50ms processing time
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                String guidance = generateDomainSpecificGuidance(request);
                List<String> recommendations = generateRecommendations(request);
                
                return AgentGuidanceResponse.success(
                        request.requestId(),
                        getId(),
                        guidance,
                        0.96, // High accuracy
                        recommendations,
                        Duration.ofMillis(50)
                );
            }
            
            private String generateDomainSpecificGuidance(AgentConsultationRequest request) {
                return switch (getDomain()) {
                    case "implementation" -> "Spring Boot implementation guidance: " + request.query();
                    case "deployment" -> "AWS deployment guidance: " + request.query();
                    case "architecture" -> "Architectural guidance: " + request.query();
                    case "testing" -> "Testing strategy guidance: " + request.query();
                    case "security" -> "Security implementation guidance: " + request.query();
                    default -> "Domain-specific guidance for " + request.domain() + ": " + request.query();
                };
            }
            
            private List<String> generateRecommendations(AgentConsultationRequest request) {
                return switch (getDomain()) {
                    case "implementation" -> List.of("Use Spring Boot 3.x", "Implement proper service layers", "Add validation");
                    case "deployment" -> List.of("Use AWS Fargate", "Configure auto-scaling", "Set up monitoring");
                    case "architecture" -> List.of("Follow DDD principles", "Maintain service boundaries", "Use event-driven patterns");
                    case "testing" -> List.of("Write unit tests", "Add integration tests", "Use test containers");
                    case "security" -> List.of("Implement JWT authentication", "Use HTTPS", "Follow OWASP guidelines");
                    default -> List.of("Follow best practices", "Maintain code quality", "Document decisions");
                };
            }
        };
    }
}
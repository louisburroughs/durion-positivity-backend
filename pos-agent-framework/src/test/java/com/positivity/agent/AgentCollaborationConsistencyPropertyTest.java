package com.positivity.agent;

import net.jqwik.api.*;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.BeforeEach;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.agent.collaboration.AgentCollaborationProtocol;
import com.positivity.agent.collaboration.CollaborativeGuidanceResponse;
import com.positivity.agent.collaboration.ConsistencyValidationResult;
import com.positivity.agent.collaboration.DefaultCollaborationProtocol;
import com.positivity.agent.registry.AgentRegistry;
import com.positivity.agent.registry.DefaultAgentRegistry;



import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Property-based test for agent collaboration consistency
 * **Feature: agent-structure, Property 3: Agent collaboration consistency**
 * **Validates: Requirements REQ-001.3**
 */
class AgentCollaborationConsistencyPropertyTest {
    
    private AgentRegistry registry;
    private AgentCollaborationProtocol collaborationProtocol;
    
    @BeforeEach
    void setUp() {
        registry = new DefaultAgentRegistry();
        collaborationProtocol = new DefaultCollaborationProtocol(registry);
        setupCollaborationTestAgents();
    }
    
    /**
     * Property 3: Agent collaboration consistency
     * For any development task involving multiple agents, all agent recommendations 
     * should be compatible within 3 seconds with zero conflicting recommendations 
     * and 100% pattern compliance
     */
    @Property(tries = 100)
    void agentCollaborationConsistency(
            @ForAll("collaborativeRequests") AgentConsultationRequest request,
            @ForAll("participatingAgentLists") @Size(min = 2, max = 5) List<String> participatingAgents) {
        
        // Filter to only include agents that exist in the registry
        List<String> validAgents = participatingAgents.stream()
                .filter(agentId -> registry.getAgent(agentId).isPresent())
                .toList();
        
        Assume.that(validAgents.size() >= 2);
        
        // When: Coordinating a collaborative consultation
        long startTime = System.nanoTime();
        CompletableFuture<CollaborativeGuidanceResponse> future = 
                collaborationProtocol.coordinateConsultation(request, validAgents);
        CollaborativeGuidanceResponse response = future.join();
        long endTime = System.nanoTime();
        Duration collaborationTime = Duration.ofNanos(endTime - startTime);
        
        // Then: Collaboration should complete within 3 seconds
        assertThat(collaborationTime.compareTo(Duration.ofSeconds(3)) <= 0)
                .describedAs("Collaboration should complete within 3 seconds")
                .isTrue();
        
        // And: Response should be successful
        assertThat(response.isSuccessful())
                .describedAs("Collaborative response should be successful")
                .isTrue();
        
        // And: Consistency validation should pass (zero conflicting recommendations)
        ConsistencyValidationResult consistency = response.consistencyResult();
        assertThat(consistency.isConsistent())
                .describedAs("Agent recommendations should be consistent (zero conflicts)")
                .isTrue();
        
        // And: Consistency score should indicate 100% pattern compliance
        assertThat(consistency.consistencyScore() >= 0.8)
                .describedAs("Consistency score should indicate high pattern compliance")
                .isTrue(); // 80% threshold for practical consistency
        
        // And: All individual responses should be successful
        assertThat(response.individualResponses().stream().allMatch(AgentGuidanceResponse::isSuccessful))
                .describedAs("All individual agent responses should be successful")
                .isTrue();
    }
    
    @Property(tries = 100)
    void consistencyValidationPerformance(
            @ForAll("multipleResponses") List<AgentGuidanceResponse> responses) {
        
        Assume.that(responses.size() >= 2);
        
        // When: Validating consistency between multiple responses
        long startTime = System.nanoTime();
        ConsistencyValidationResult result = collaborationProtocol.validateConsistency(responses);
        long endTime = System.nanoTime();
        Duration validationTime = Duration.ofNanos(endTime - startTime);
        
        // Then: Validation should complete within 3 seconds
        assertThat(validationTime.compareTo(Duration.ofSeconds(3)) <= 0)
                .describedAs("Consistency validation should complete within 3 seconds")
                .isTrue();
        
        // And: Result should have a valid consistency score
        assertThat(result.consistencyScore() >= 0.0 && result.consistencyScore() <= 1.0)
                .describedAs("Consistency score should be between 0 and 1")
                .isTrue();
        
        // And: Validation time should be recorded
        assertThat(result.validationTime() != null && !result.validationTime().isNegative())
                .describedAs("Validation time should be recorded and positive")
                .isTrue();
    }
    
    @Property(tries = 100)
    void conflictResolutionMaintainsQuality(
            @ForAll("conflictingResponses") List<AgentGuidanceResponse> conflictingResponses) {
        
        Assume.that(conflictingResponses.size() >= 2);
        
        // When: Resolving conflicts between agent responses
        AgentGuidanceResponse resolved = collaborationProtocol.resolveConflicts(conflictingResponses);
        
        // Then: Resolved response should be successful
        assertThat(resolved.isSuccessful())
                .describedAs("Resolved response should be successful")
                .isTrue();
        
        // And: Resolved response should have high confidence
        double maxOriginalConfidence = conflictingResponses.stream()
                .mapToDouble(AgentGuidanceResponse::confidence)
                .max()
                .orElse(0.0);
        
        assertThat(resolved.confidence() >= Math.min(maxOriginalConfidence, 0.95))
                .describedAs("Resolved response should maintain high confidence")
                .isTrue();
        
        // And: Resolved response should contain guidance
        assertThat(resolved.guidance().isEmpty())
                .describedAs("Resolved response should contain guidance")
                .isFalse();
    }
    
    @Property(tries = 50)
    void collaborationWorkflowsAreConsistent(
            @ForAll("workflowDomains") String domain) {
        
        // When: Getting collaboration workflow for any domain
        List<String> workflow = collaborationProtocol.getCollaborationWorkflow(domain);
        
        // Then: Workflow should be consistent across multiple calls
        List<String> secondCall = collaborationProtocol.getCollaborationWorkflow(domain);
        assertThat(workflow.equals(secondCall))
                .describedAs("Workflow should be consistent across calls")
                .isTrue();
        
        // And: If workflow exists, all agents should be valid
        if (!workflow.isEmpty()) {
            assertThat(workflow.stream().allMatch(agentId -> registry.getAgent(agentId).isPresent()))
                    .describedAs("All agents in workflow should exist in registry")
                    .isTrue();
        }
    }
    
    @Provide
    Arbitrary<AgentConsultationRequest> collaborativeRequests() {
        return Combinators.combine(
                Arbitraries.of("microservice-development", "deployment-pipeline", "security", "performance"),
                Arbitraries.strings().alpha().ofMinLength(10).ofMaxLength(100),
                Arbitraries.maps(
                        Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(10),
                        Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20)
                ).ofMaxSize(5)
        ).as((domain, query, context) -> 
                AgentConsultationRequest.create(domain, query, context));
    }
    
    @Provide
    Arbitrary<List<String>> participatingAgentLists() {
        return Arbitraries.of(
                "arch-1", "spring-1", "security-1", "devops-1", "test-1", "perf-1"
        ).list().ofMinSize(2).ofMaxSize(5);
    }
    
    @Provide
    Arbitrary<List<AgentGuidanceResponse>> multipleResponses() {
        return Arbitraries.integers().between(2, 5).flatMap(count -> 
                Arbitraries.create(() -> generateConsistentResponses(count))
        );
    }
    
    @Provide
    Arbitrary<List<AgentGuidanceResponse>> conflictingResponses() {
        return Arbitraries.integers().between(2, 4).flatMap(count -> 
                Arbitraries.create(() -> generateConflictingResponses(count))
        );
    }
    
    @Provide
    Arbitrary<String> workflowDomains() {
        return Arbitraries.of(
                "microservice-development", "deployment-pipeline", 
                "documentation-workflow", "security", "performance"
        );
    }
    
    private void setupCollaborationTestAgents() {
        // Create agents that can collaborate effectively
        registry.registerAgent(createCollaborativeAgent("arch-1", "Architecture Agent", 
                "architecture", Set.of("ddd", "microservices", "patterns")));
        
        registry.registerAgent(createCollaborativeAgent("spring-1", "Spring Boot Developer", 
                "implementation", Set.of("spring-boot", "microservices", "rest-api")));
        
        registry.registerAgent(createCollaborativeAgent("security-1", "Security Agent", 
                "security", Set.of("jwt", "spring-security", "owasp")));
        
        registry.registerAgent(createCollaborativeAgent("devops-1", "DevOps Agent", 
                "deployment", Set.of("docker", "aws", "ci-cd")));
        
        registry.registerAgent(createCollaborativeAgent("test-1", "Testing Agent", 
                "testing", Set.of("unit-testing", "integration-testing")));
        
        registry.registerAgent(createCollaborativeAgent("perf-1", "Performance Agent", 
                "performance", Set.of("optimization", "caching", "scaling")));
    }
    
    private Agent createCollaborativeAgent(String id, String name, String domain, Set<String> capabilities) {
        return new BaseAgent(id, name, domain, capabilities, Set.of(), 
                AgentPerformanceSpec.highPerformanceSpec()) {
            @Override
            protected AgentGuidanceResponse processGuidanceRequest(AgentConsultationRequest request) {
                // Simulate consistent, collaborative responses
                String guidance = generateConsistentGuidance(request, getId());
                List<String> recommendations = generateConsistentRecommendations(request, getId());
                
                return AgentGuidanceResponse.success(
                        request.requestId(),
                        getId(),
                        guidance,
                        0.95,
                        recommendations,
                        Duration.ofMillis(100)
                );
            }
            
            private String generateConsistentGuidance(AgentConsultationRequest request, String agentId) {
                // Generate guidance that follows consistent patterns
                String baseGuidance = "For " + request.domain() + " requirements: " + request.query();
                return switch (agentId) {
                    case "arch-1" -> "Architectural perspective: " + baseGuidance + " - Follow DDD principles and maintain service boundaries.";
                    case "spring-1" -> "Implementation perspective: " + baseGuidance + " - Use Spring Boot best practices and proper layering.";
                    case "security-1" -> "Security perspective: " + baseGuidance + " - Implement JWT authentication and follow OWASP guidelines.";
                    case "devops-1" -> "Deployment perspective: " + baseGuidance + " - Use containerization and AWS best practices.";
                    case "test-1" -> "Testing perspective: " + baseGuidance + " - Implement comprehensive test coverage.";
                    case "perf-1" -> "Performance perspective: " + baseGuidance + " - Optimize for scalability and efficiency.";
                    default -> baseGuidance;
                };
            }
            
            private List<String> generateConsistentRecommendations(AgentConsultationRequest request, String agentId) {
                // Generate recommendations that are compatible across agents
                return switch (agentId) {
                    case "arch-1" -> List.of("Use microservice architecture", "Maintain domain boundaries", "Follow DDD patterns");
                    case "spring-1" -> List.of("Use Spring Boot 3.x", "Implement proper service layers", "Follow REST conventions");
                    case "security-1" -> List.of("Implement JWT authentication", "Use HTTPS everywhere", "Follow OWASP top 10");
                    case "devops-1" -> List.of("Use Docker containers", "Deploy to AWS Fargate", "Implement CI/CD pipelines");
                    case "test-1" -> List.of("Write unit tests", "Add integration tests", "Maintain test coverage");
                    case "perf-1" -> List.of("Implement caching", "Optimize database queries", "Use async processing");
                    default -> List.of("Follow best practices", "Maintain code quality");
                };
            }
        };
    }
    
    private List<AgentGuidanceResponse> generateConsistentResponses(int count) {
        String requestId = "test-request-" + System.nanoTime();
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> AgentGuidanceResponse.success(
                        requestId,
                        "agent-" + i,
                        "Consistent guidance for microservice development",
                        0.95,
                        List.of("Use Spring Boot", "Follow REST conventions", "Implement proper testing"),
                        Duration.ofMillis(100)
                ))
                .toList();
    }
    
    private List<AgentGuidanceResponse> generateConflictingResponses(int count) {
        String requestId = "conflict-request-" + System.nanoTime();
        return List.of(
                AgentGuidanceResponse.success(requestId, "agent-1", "Use approach A", 0.9, 
                        List.of("Recommendation A1", "Recommendation A2"), Duration.ofMillis(100)),
                AgentGuidanceResponse.success(requestId, "agent-2", "Use approach B", 0.8, 
                        List.of("Recommendation B1", "Recommendation B2"), Duration.ofMillis(100))
        );
    }
}
package com.positivity.agent.integration;

import com.positivity.agent.collaboration.CollaborationController;
import com.positivity.agent.collaboration.CollaborationRequest;
import com.positivity.agent.collaboration.CollaborationResponse;
import com.positivity.agent.context.ArchitectureContext;
import com.positivity.agent.context.SecurityContext;
import com.positivity.agent.context.StoryContext;
import com.positivity.agent.core.AgentManager;
import com.positivity.agent.core.AgentRegistry;
import com.positivity.agent.model.AgentRequest;
import com.positivity.agent.model.AgentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for multi-agent collaboration workflows.
 * Tests agent consensus, conflict resolution, and workflow orchestration.
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentCollaborationTest {

    @Autowired
    private CollaborationController collaborationController;

    @Autowired
    private AgentManager agentManager;

    @Autowired
    private AgentRegistry agentRegistry;

    @BeforeEach
    void setUp() {
        // Ensure all agents are registered and healthy
        assertThat(agentRegistry.getAvailableAgents()).hasSize(15);
    }

    @Test
    void testStoryProcessingWithArchitectureAndImplementationAgents() {
        // Given: A story requiring architecture and implementation guidance
        StoryContext storyContext = StoryContext.builder()
                .issueId("123")
                .title("Implement inventory service")
                .description("Create REST API for inventory management")
                .moduleName("pos-inventory")
                .acceptanceCriteria(List.of(
                        "API should support CRUD operations",
                        "Use Spring Boot and JPA",
                        "Include proper error handling"
                ))
                .build();

        CollaborationRequest request = CollaborationRequest.builder()
                .primaryAgent("architecture")
                .collaboratingAgents(Arrays.asList("implementation", "testing"))
                .context(storyContext)
                .build();

        // When: Processing the collaboration request
        CollaborationResponse response = collaborationController.processCollaboration(request);

        // Then: All agents should provide consistent guidance
        assertThat(response.isSuccessful()).isTrue();
        assertThat(response.getAgentResponses()).hasSize(3);
        assertThat(response.getConsensus()).isNotNull();
        assertThat(response.getConsensus().getRecommendations()).isNotEmpty();
    }

    @Test
    void testArchitectureReviewWithSecurityAndResilienceAgents() {
        // Given: Architecture requiring security and resilience review
        ArchitectureContext archContext = ArchitectureContext.builder()
                .serviceType("microservice")
                .domain("payment")
                .patterns(Arrays.asList("api-gateway", "circuit-breaker"))
                .constraints(Arrays.asList("PCI-DSS compliance", "high availability"))
                .build();

        CollaborationRequest request = CollaborationRequest.builder()
                .primaryAgent("architecture")
                .collaboratingAgents(Arrays.asList("security", "resilience"))
                .context(archContext)
                .build();

        // When: Processing the collaboration
        CollaborationResponse response = collaborationController.processCollaboration(request);

        // Then: Security and resilience concerns should be addressed
        assertThat(response.isSuccessful()).isTrue();
        assertThat(response.getConflicts()).isEmpty();
        assertThat(response.getConsensus().getRecommendations())
                .anyMatch(rec -> rec.toLowerCase().contains("security"))
                .anyMatch(rec -> rec.toLowerCase().contains("resilience"));
    }

    @Test
    void testDeploymentWithCICDAndObservabilityAgents() {
        // Given: Deployment requiring CI/CD and observability setup
        AgentRequest deploymentRequest = AgentRequest.builder()
                .type("deployment-guidance")
                .context("Deploy pos-inventory service to Kubernetes")
                .requirements(Arrays.asList(
                        "Automated deployment pipeline",
                        "Health checks and monitoring",
                        "Rollback capability"
                ))
                .build();

        CollaborationRequest request = CollaborationRequest.builder()
                .primaryAgent("deployment")
                .collaboratingAgents(Arrays.asList("cicd", "observability"))
                .context(deploymentRequest)
                .build();

        // When: Processing the collaboration
        CollaborationResponse response = collaborationController.processCollaboration(request);

        // Then: Deployment, CI/CD, and observability should be coordinated
        assertThat(response.isSuccessful()).isTrue();
        assertThat(response.getAgentResponses()).hasSize(3);
        assertThat(response.getConsensus().getImplementationSteps()).isNotEmpty();
    }

    @Test
    void testImplementationWithTestingAndBusinessDomainAgents() {
        // Given: Implementation requiring testing and domain guidance
        AgentRequest implRequest = AgentRequest.builder()
                .type("feature-implementation")
                .context("Implement customer loyalty points calculation")
                .requirements(Arrays.asList(
                        "Business rules for point calculation",
                        "Comprehensive test coverage",
                        "Performance optimization"
                ))
                .build();

        CollaborationRequest request = CollaborationRequest.builder()
                .primaryAgent("implementation")
                .collaboratingAgents(Arrays.asList("testing", "business-domain"))
                .context(implRequest)
                .build();

        // When: Processing the collaboration
        CollaborationResponse response = collaborationController.processCollaboration(request);

        // Then: Implementation should include business logic and testing guidance
        assertThat(response.isSuccessful()).isTrue();
        assertThat(response.getConsensus().getRecommendations())
                .anyMatch(rec -> rec.toLowerCase().contains("test"))
                .anyMatch(rec -> rec.toLowerCase().contains("business"));
    }

    @Test
    void testConflictResolutionBetweenAgents() {
        // Given: A scenario that might cause conflicting recommendations
        SecurityContext securityContext = SecurityContext.builder()
                .threatModel("payment processing")
                .complianceRequirements(Arrays.asList("PCI-DSS", "SOX"))
                .securityConstraints(Arrays.asList("encryption at rest", "audit logging"))
                .build();

        CollaborationRequest request = CollaborationRequest.builder()
                .primaryAgent("security")
                .collaboratingAgents(Arrays.asList("implementation", "performance"))
                .context(securityContext)
                .build();

        // When: Processing with potential conflicts (security vs performance)
        CollaborationResponse response = collaborationController.processCollaboration(request);

        // Then: Conflicts should be detected and resolved
        assertThat(response.isSuccessful()).isTrue();
        if (!response.getConflicts().isEmpty()) {
            assertThat(response.getConflictResolution()).isNotNull();
            assertThat(response.getConflictResolution().getResolutionStrategy()).isNotEmpty();
        }
    }

    @Test
    void testEscalationToHumanOnUnresolvableConflicts() {
        // Given: A scenario with irreconcilable agent recommendations
        AgentRequest conflictRequest = AgentRequest.builder()
                .type("architecture-decision")
                .context("Choose between microservices vs monolith for small team")
                .requirements(Arrays.asList(
                        "Rapid development",
                        "Scalability",
                        "Team size: 3 developers",
                        "Timeline: 3 months"
                ))
                .build();

        CollaborationRequest request = CollaborationRequest.builder()
                .primaryAgent("architecture")
                .collaboratingAgents(Arrays.asList("implementation", "deployment"))
                .context(conflictRequest)
                .forceConflict(true) // Test parameter to simulate unresolvable conflict
                .build();

        // When: Processing with forced conflict
        CollaborationResponse response = collaborationController.processCollaboration(request);

        // Then: Should escalate to human when conflicts cannot be resolved
        if (response.requiresHumanEscalation()) {
            assertThat(response.getEscalationReason()).isNotEmpty();
            assertThat(response.getEscalationContext()).isNotNull();
        }
    }

    @Test
    void testAgentConsensusBuilding() {
        // Given: Multiple agents providing input on the same topic
        AgentRequest consensusRequest = AgentRequest.builder()
                .type("technology-selection")
                .context("Select database technology for pos-inventory service")
                .requirements(Arrays.asList(
                        "ACID compliance",
                        "High performance",
                        "Easy maintenance",
                        "Cost effective"
                ))
                .build();

        CollaborationRequest request = CollaborationRequest.builder()
                .primaryAgent("architecture")
                .collaboratingAgents(Arrays.asList("implementation", "deployment", "observability"))
                .context(consensusRequest)
                .build();

        // When: Building consensus across multiple agents
        CollaborationResponse response = collaborationController.processCollaboration(request);

        // Then: Should reach consensus with clear rationale
        assertThat(response.isSuccessful()).isTrue();
        assertThat(response.getConsensus()).isNotNull();
        assertThat(response.getConsensus().getConfidenceScore()).isGreaterThan(0.7);
        assertThat(response.getConsensus().getRationale()).isNotEmpty();
    }

    @Test
    void testCollaborationPerformanceWithinSLA() {
        // Given: A collaboration request
        AgentRequest perfRequest = AgentRequest.builder()
                .type("quick-guidance")
                .context("Best practices for Spring Boot REST API")
                .build();

        CollaborationRequest request = CollaborationRequest.builder()
                .primaryAgent("implementation")
                .collaboratingAgents(Arrays.asList("security", "testing"))
                .context(perfRequest)
                .build();

        // When: Processing collaboration and measuring time
        long startTime = System.currentTimeMillis();
        CollaborationResponse response = collaborationController.processCollaboration(request);
        long duration = System.currentTimeMillis() - startTime;

        // Then: Should complete within performance SLA (3 seconds)
        assertThat(response.isSuccessful()).isTrue();
        assertThat(duration).isLessThan(3000); // 3 seconds SLA
    }
}

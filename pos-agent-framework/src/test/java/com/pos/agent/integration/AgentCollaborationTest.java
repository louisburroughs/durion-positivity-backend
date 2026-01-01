package com.pos.agent.integration;

import com.pos.agent.core.AgentManager;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.context.AgentContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for multi-agent collaboration workflows.
 * Tests agent consensus, conflict resolution, and workflow orchestration.
 */
class AgentCollaborationTest {

        private AgentManager agentManager;

        @BeforeEach
        void setUp() {
                agentManager = new AgentManager();
        }

        @Test
        void testStoryProcessingWithArchitectureAndImplementationAgents() {
                AgentContext storyContext = AgentContext.builder()
                                .domain("inventory")
                                .property("issueId", "123")
                                .property("title", "Implement inventory service")
                                .property("description", "Create REST API for inventory management")
                                .property("moduleName", "pos-inventory")
                                .property("acceptanceCriteria", List.of(
                                                "API should support CRUD operations",
                                                "Use Spring Boot and JPA",
                                                "Include proper error handling"))
                                .build();

                AgentResponse arch = agentManager.processRequest(AgentRequest.builder()
                                .type("architecture")
                                .description("Architecture review for inventory REST API")
                                .context(storyContext)
                                .build());

                AgentResponse impl = agentManager.processRequest(AgentRequest.builder()
                                .type("implementation")
                                .description("Implementation guidance for inventory service")
                                .context(storyContext)
                                .build());

                AgentResponse test = agentManager.processRequest(AgentRequest.builder()
                                .type("testing")
                                .description("Testing strategy for inventory service")
                                .context(storyContext)
                                .build());

                assertTrue(arch.isSuccess());
                assertTrue(impl.isSuccess());
                assertTrue(test.isSuccess());
        }

        @Test
        void testArchitectureReviewWithSecurityAndResilienceAgents() {
                AgentContext archContext = AgentContext.builder()
                                .serviceType("microservice")
                                .domain("payment")
                                .property("patterns", List.of("api-gateway", "circuit-breaker"))
                                .property("constraints", List.of("PCI-DSS compliance", "high availability"))
                                .build();

                AgentResponse arch = agentManager.processRequest(AgentRequest.builder()
                                .type("architecture")
                                .description("Architecture review for payment microservice")
                                .context(archContext)
                                .build());

                AgentResponse sec = agentManager.processRequest(AgentRequest.builder()
                                .type("security")
                                .description("Security analysis for payment architecture")
                                .context(archContext)
                                .build());

                AgentResponse resil = agentManager.processRequest(AgentRequest.builder()
                                .type("resilience-engineering")
                                .description("Resilience engineering for payment service")
                                .context(archContext)
                                .build());

                assertTrue(arch.isSuccess());
                assertTrue(sec.isSuccess());
                assertTrue(resil.isSuccess());
        }

        @Test
        void testDeploymentWithCICDAndObservabilityAgents() {
                AgentContext deployContext = AgentContext.builder()
                                .domain("inventory")
                                .property("description", "Deploy pos-inventory service to Kubernetes")
                                .property("requirements", List.of(
                                                "Automated deployment pipeline",
                                                "Health checks and monitoring",
                                                "Rollback capability"))
                                .build();

                AgentResponse deploy = agentManager.processRequest(AgentRequest.builder()
                                .type("deployment")
                                .description("Deployment planning for pos-inventory to Kubernetes")
                                .context(deployContext)
                                .build());

                AgentResponse cicd = agentManager.processRequest(AgentRequest.builder()
                                .type("cicd-pipeline")
                                .description("CI/CD pipeline setup for inventory service")
                                .context(deployContext)
                                .build());

                AgentResponse observ = agentManager.processRequest(AgentRequest.builder()
                                .type("observability")
                                .description("Observability setup for inventory service monitoring")
                                .context(deployContext)
                                .build());

                assertTrue(deploy.isSuccess());
                assertTrue(cicd.isSuccess());
                assertTrue(observ.isSuccess());
        }

        @Test
        void testImplementationWithTestingAndBusinessDomainAgents() {
                AgentContext implContext = AgentContext.builder()
                                .domain("customer")
                                .property("description", "Implement customer loyalty points calculation")
                                .property("requirements", List.of(
                                                "Business rules for point calculation",
                                                "Comprehensive test coverage",
                                                "Performance optimization"))
                                .build();

                AgentResponse impl = agentManager.processRequest(AgentRequest.builder()
                                .type("implementation")
                                .description("Implementation of customer loyalty points calculation")
                                .context(implContext)
                                .build());

                AgentResponse testing = agentManager.processRequest(AgentRequest.builder()
                                .type("testing")
                                .description("Testing strategy for loyalty points feature")
                                .context(implContext)
                                .build());

                AgentResponse domain = agentManager.processRequest(AgentRequest.builder()
                                .type("business-domain")
                                .description("Business domain analysis for customer loyalty program")
                                .context(implContext)
                                .build());

                assertTrue(impl.isSuccess());
                assertTrue(testing.isSuccess());
                assertTrue(domain.isSuccess());
        }

        @Test
        void testConflictResolutionBetweenAgents() {
                AgentContext secContext = AgentContext.builder()
                                .domain("payment")
                                .property("threatModel", "payment processing")
                                .property("complianceRequirements", List.of("PCI-DSS", "SOX"))
                                .property("securityConstraints", List.of("encryption at rest", "audit logging"))
                                .build();

                AgentResponse sec = agentManager.processRequest(AgentRequest.builder()
                                .type("security")
                                .description("Security analysis for payment processing")
                                .context(secContext)
                                .build());
                AgentResponse impl = agentManager.processRequest(AgentRequest.builder()
                                .type("implementation")
                                .description("Implementation approach for secure payment processing")
                                .context(secContext)
                                .build());
                AgentResponse perf = agentManager.processRequest(AgentRequest.builder()
                                .type("performance")
                                .description("Performance optimization for payment processing")
                                .context(secContext)
                                .build());

                assertTrue(sec.isSuccess());
                assertTrue(impl.isSuccess());
                assertTrue(perf.isSuccess());
        }

        @Test
        void testEscalationToHumanOnUnresolvableConflicts() {
                AgentContext decisionContext = AgentContext.builder()
                                .domain("architecture")
                                .property("decision", "Choose between microservices vs monolith for small team")
                                .property("criteria", List.of(
                                                "Rapid development",
                                                "Scalability",
                                                "Team size: 3 developers",
                                                "Timeline: 3 months"))
                                .build();

                AgentResponse arch = agentManager.processRequest(AgentRequest.builder()
                                .type("architecture")
                                .description("Architecture decision: microservices vs monolith")
                                .context(decisionContext)
                                .build());
                AgentResponse impl = agentManager.processRequest(AgentRequest.builder()
                                .type("implementation")
                                .description("Implementation perspective on architecture decision")
                                .context(decisionContext)
                                .build());
                AgentResponse deploy = agentManager.processRequest(AgentRequest.builder()
                                .type("deployment")
                                .description("Deployment considerations for architecture decision")
                                .context(decisionContext)
                                .build());

                assertTrue(arch.isSuccess());
                assertTrue(impl.isSuccess());
                assertTrue(deploy.isSuccess());
        }

        @Test
        void testAgentConsensusBuilding() {
                AgentContext techSelection = AgentContext.builder()
                                .domain("inventory")
                                .property("topic", "Select database technology for pos-inventory service")
                                .property("requirements", List.of(
                                                "ACID compliance",
                                                "High performance",
                                                "Easy maintenance",
                                                "Cost effective"))
                                .build();

                AgentResponse arch = agentManager.processRequest(AgentRequest.builder()
                                .type("architecture")
                                .description("Architecture perspective on database technology selection")
                                .context(techSelection)
                                .build());
                AgentResponse impl = agentManager.processRequest(AgentRequest.builder()
                                .type("implementation")
                                .description("Implementation perspective on database technology selection")
                                .context(techSelection)
                                .build());
                AgentResponse deploy = agentManager.processRequest(AgentRequest.builder()
                                .type("deployment")
                                .description("Deployment perspective on database technology selection")
                                .context(techSelection)
                                .build());
                AgentResponse observ = agentManager.processRequest(AgentRequest.builder()
                                .type("observability")
                                .description("Observability perspective on database technology selection")
                                .context(techSelection)
                                .build());

                assertTrue(arch.isSuccess());
                assertTrue(impl.isSuccess());
                assertTrue(deploy.isSuccess());
                assertTrue(observ.isSuccess());
        }

        @Test
        void testCollaborationPerformanceWithinSLA() {
                AgentContext perfContext = AgentContext.builder()
                                .domain("implementation")
                                .property("topic", "Best practices for Spring Boot REST API")
                                .build();

                CompletableFuture<AgentResponse> impl = CompletableFuture
                                .supplyAsync(() -> agentManager.processRequest(AgentRequest.builder()
                                                .type("implementation")
                                                .description("Implementation best practices for Spring Boot REST API")
                                                .context(perfContext)
                                                .build()));

                CompletableFuture<AgentResponse> sec = CompletableFuture
                                .supplyAsync(() -> agentManager.processRequest(AgentRequest.builder()
                                                .type("security")
                                                .description("Security best practices for Spring Boot REST API")
                                                .context(perfContext)
                                                .build()));

                CompletableFuture<AgentResponse> test = CompletableFuture
                                .supplyAsync(() -> agentManager.processRequest(AgentRequest.builder()
                                                .type("testing")
                                                .description("Testing best practices for Spring Boot REST API")
                                                .context(perfContext)
                                                .build()));

                assertTimeoutPreemptively(Duration.ofSeconds(3), () -> CompletableFuture.allOf(impl, sec, test).join());

                assertTrue(impl.join().isSuccess());
                assertTrue(sec.join().isSuccess());
                assertTrue(test.join().isSuccess());
        }
}

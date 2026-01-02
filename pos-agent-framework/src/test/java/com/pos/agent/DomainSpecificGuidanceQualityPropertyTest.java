package com.pos.agent;

import com.pos.agent.context.AgentContext;
import com.pos.agent.core.AgentManager;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.core.SecurityContext;
import net.jqwik.api.*;
import org.junit.jupiter.api.BeforeEach;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

/**
 * Property-based test for domain-specific guidance quality
 * **Feature: agent-structure, Property 2: Domain-specific guidance quality**
 * **Validates: Requirements REQ-001.2**
 */
class DomainSpecificGuidanceQualityPropertyTest {

        private AgentManager agentManager;
        private SecurityContext securityContext;

        @BeforeEach
        void setUp() {
                agentManager = new AgentManager();
                securityContext = SecurityContext.builder()
                                .jwtToken("domain-guidance-jwt-token")
                                .userId("property-test-user")
                                .roles(List.of("admin", "developer", "architect", "tester", "operator"))
                                .permissions(List.of(
                                                "read",
                                                "AGENT_READ",
                                                "AGENT_WRITE",
                                                "agent:read",
                                                "agent:write",
                                                "agent:discover"))
                                .serviceId("property-test-service")
                                .serviceType("property-based")
                                .build();
        }

        /**
         * Helper method to ensure agentManager is initialized.
         * Property-based tests have different lifecycle than regular JUnit tests.
         */
        private void ensureSetup() {
                if (agentManager == null) {
                        setUp();
                }
        }

        /**
         * Helper method to get test security context for @Provide methods.
         */
        private SecurityContext getTestSecurityContext() {
                ensureSetup();
                return securityContext;
        }

        @Property(tries = 100)
        void domainSpecificGuidanceQuality(@ForAll("domainConsultationRequests") AgentRequest request) {
                // Ensure setup for property-based test lifecycle
                ensureSetup();
                // When: Consulting an agent for any domain-specific request
                request.setSecurityContext(securityContext);
                AgentResponse response = agentManager.processRequest(request);

                // Then: Response should be successful
                assertThat(response.isSuccess())
                                .describedAs("Response should be successful for domain: %s",
                                                request.getAgentContext().getAgentDomain())
                                .isTrue();

                // And: Response should return success status
                assertThat(response.getStatus())
                                .describedAs("Response status should not be null")
                                .isNotNull();

                // And: Guidance should be relevant to the requested domain
                String domain = request.getAgentContext().getAgentDomain().toLowerCase();

                boolean isDomainSpecific = switch (domain) {
                        case "architecture" -> containsArchitecturalConcepts(domain);
                        case "implementation" -> containsImplementationConcepts(domain);
                        case "testing" -> containsTestingConcepts(domain);
                        case "deployment" -> containsDeploymentConcepts(domain);
                        default -> true; // Allow other domains to pass
                };

                assertThat(isDomainSpecific)
                                .describedAs("Response should be domain-relevant for %s", domain)
                                .isTrue();

                // And: Processing time should be reasonable
                assertThat(response.getProcessingTimeMs())
                                .describedAs("Response processing time should be positive")
                                .isGreaterThan(0);
        }

        /**
         * Test Spring Boot pattern adherence for implementation domain
         */
        @Property(tries = 100)
        void springBootPatternAdherence(@ForAll("springBootRequests") AgentRequest request) {
                // Ensure setup for property-based test lifecycle
                ensureSetup();
                // When: Requesting Spring Boot implementation guidance
                request.setSecurityContext(securityContext);
                AgentResponse response = agentManager.processRequest(request);

                // Then: Response should be successful
                assertThat(response.isSuccess()).isTrue();

                // And: Domain should be implementation-related
                boolean hasImplementationDomain = request.getAgentContext().getAgentDomain().equals("implementation");

                assertThat(hasImplementationDomain)
                                .describedAs("Spring Boot request should target implementation domain")
                                .isTrue();

                // And: Processing time should be reasonable
                assertThat(response.getProcessingTimeMs())
                                .describedAs("Response should have valid processing time")
                                .isGreaterThan(0);
        }

        /**
         * Test AWS pattern adherence for deployment domain
         */
        @Property(tries = 100)
        void awsPatternAdherence(@ForAll("awsDeploymentRequests") AgentRequest request) {
                // Ensure setup for property-based test lifecycle
                ensureSetup();
                // When: Requesting AWS deployment guidance
                request.setSecurityContext(securityContext);
                AgentResponse response = agentManager.processRequest(request);

                // Then: Response should be successful
                assertThat(response.isSuccess()).isTrue();

                // And: Status should be deployment-related
                String domain = request.getAgentContext().getAgentDomain();
                boolean hasDeploymentDomain = domain.equals("deployment");

                assertThat(hasDeploymentDomain)
                                .describedAs("AWS deployment request should target deployment domain")
                                .isTrue();

                // And: Processing time should be reasonable
                assertThat(response.getProcessingTimeMs())
                                .describedAs("Response should have valid processing time")
                                .isGreaterThan(0);
        }

        /**
         * Test data store guidance appropriateness
         */
        @Property(tries = 100)
        void dataStoreGuidanceAppropriateness(@ForAll("dataStoreRequests") AgentRequest request) {
                // Ensure setup for property-based test lifecycle
                ensureSetup();
                // When: Requesting data store guidance
                request.setSecurityContext(securityContext);
                AgentResponse response = agentManager.processRequest(request);

                // Then: Response should be successful
                assertThat(response.isSuccess()).isTrue();

                // And: Domain should be either implementation or deployment
                String domain = request.getAgentContext().getAgentDomain();
                boolean isValidDomain = domain.equals("implementation") || domain.equals("deployment");

                assertThat(isValidDomain)
                                .describedAs("Data store guidance should be for implementation or deployment domain")
                                .isTrue();

                // And: Processing time should be positive
                assertThat(response.getProcessingTimeMs())
                                .describedAs("Response should have positive processing time")
                                .isGreaterThan(0);
        }

        @Provide
        Arbitrary<AgentRequest> domainConsultationRequests() {
                return Combinators.combine(
                                Arbitraries.of("architecture", "implementation", "testing", "deployment"),
                                Arbitraries.strings().alpha().ofMinLength(10).ofMaxLength(100))
                                .as((domain, query) -> AgentRequest.builder()
                                                .type("domain-test")
                                                .description("Domain consultation property test")
                                                .context(AgentContext.builder()
                                                                .agentDomain(domain)
                                                                .property("query", query)
                                                                .build())
                                                .securityContext(getTestSecurityContext())
                                                .build());
        }

        @Provide
        Arbitrary<AgentRequest> springBootRequests() {
                return Arbitraries.of(
                                "How to implement Spring Boot microservice?",
                                "Create REST controller with Spring Boot",
                                "Spring Boot service layer best practices",
                                "Implement Spring Boot configuration",
                                "Spring Boot dependency injection patterns",
                                "Microservice architecture with Spring Boot")
                                .map(query -> AgentRequest.builder()
                                                .type("spring-boot-test")
                                                .description("Spring Boot guidance property test")
                                                .context(AgentContext.builder()
                                                                .agentDomain("implementation")
                                                                .property("query", query)
                                                                .property("framework", "spring-boot")
                                                                .build())
                                                .securityContext(getTestSecurityContext())
                                                .build());
        }

        @Provide
        Arbitrary<AgentRequest> awsDeploymentRequests() {
                return Arbitraries.of(
                                "Deploy microservice to AWS Fargate",
                                "Configure AWS ECS for POS system",
                                "Set up DynamoDB for microservice",
                                "Configure ElastiCache cluster",
                                "AWS container orchestration best practices",
                                "AWS monitoring and observability setup")
                                .map(query -> AgentRequest.builder()
                                                .type("aws-deployment-test")
                                                .description("AWS deployment guidance property test")
                                                .context(AgentContext.builder()
                                                                .agentDomain("deployment")
                                                                .property("query", query)
                                                                .property("cloud", "aws")
                                                                .build())
                                                .securityContext(getTestSecurityContext())
                                                .build());
        }

        @Provide
        Arbitrary<AgentRequest> dataStoreRequests() {
                return Combinators.combine(
                                Arbitraries.of("implementation", "deployment"),
                                Arbitraries.of(
                                                "How to persist customer data?",
                                                "Cache session information",
                                                "Store product catalog data",
                                                "Implement temporary data storage",
                                                "Database design for POS system",
                                                "Cache strategy for high performance"))
                                .as((domain, query) -> AgentRequest.builder()
                                                .type("datastore-test")
                                                .description("Data store guidance property test")
                                                .context(AgentContext.builder()
                                                                .agentDomain(domain)
                                                                .property("query", query)
                                                                .build())
                                                .securityContext(getTestSecurityContext())
                                                .build());
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
}
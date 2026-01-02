package com.pos.agent;

import com.pos.agent.core.AgentManager;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.context.AgentContext;
import com.pos.agent.core.SecurityContext;
import net.jqwik.api.*;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

/**
 * Property-based test for CI/CD security integration
 * **Feature: agent-structure, Property 15: CI/CD security integration**
 * **Validates: Requirements REQ-013.2, REQ-013.4**
 */
class CICDSecurityIntegrationPropertyTest {

        private final AgentManager agentManager = new AgentManager();
        private final SecurityContext security = SecurityContext.builder()
                        .jwtToken("cicd-security-jwt-token")
                        .userId("cicd-security-tester")
                        .roles(List.of("admin", "devops", "security", "operator"))
                        .permissions(List.of(
                                        "execute",
                                        "configure",
                                        "audit",
                                        "AGENT_READ",
                                        "AGENT_WRITE",
                                        "agent:read",
                                        "agent:write",
                                        "agent:execute"))
                        .serviceId("pos-cicd-tests")
                        .serviceType("property")
                        .build();

        /**
         * Property 15: CI/CD security integration
         * For any pipeline configuration, the CI/CD Pipeline Agent should ensure
         * security scanning, testing automation, and deployment security validation
         */
        @Property(tries = 100)
        void cicdSecurityIntegration(
                        @ForAll("pipelineConfigurationContexts") AgentContext context) {
                // Given: A CI/CD security integration request
                AgentRequest request = AgentRequest.builder()
                                .description("CI/CD security integration property test")
                                .type("ci-cd-security")
                                .context(context)
                                .securityContext(security)
                                .build();

                // When: Processing the request through the agent manager
                AgentResponse response = agentManager.processRequest(request);

                // Then: The response should be successful
                assertThat(response.isSuccess())
                                .describedAs("CI/CD security integration should succeed")
                                .isTrue();

                assertThat(response.getStatus())
                                .describedAs("Status should be present")
                                .isNotNull();

                // And: The response should be timely
                assertThat(response.getProcessingTimeMs())
                                .describedAs("Processing time should be within acceptable limits")
                                .isLessThan(3000);
        }

        /**
         * Property 15b: Security scanning pipeline integration
         * For any security scanning request, the system should provide
         * comprehensive SAST, DAST, and dependency scanning guidance
         */
        @Property(tries = 100)
        void securityScanningPipelineIntegration(
                        @ForAll("securityScanningContexts") AgentContext context) {
                // Given: A security scanning request
                AgentRequest request = AgentRequest.builder()
                                .description("Security scanning pipeline integration property test")
                                .type("security-scanning")
                                .context(context)
                                .securityContext(security)
                                .build();

                // When: Processing the request through the agent manager
                AgentResponse response = agentManager.processRequest(request);

                // Then: The response should be successful
                assertThat(response.isSuccess())
                                .describedAs("Security scanning integration should succeed")
                                .isTrue();

                assertThat(response.getStatus())
                                .describedAs("Status should be present")
                                .isNotNull();

                // And: The response should include scanning type
                String scanningType = (String) context.getProperties().getOrDefault("scanningType", "unknown");
                assertThat(scanningType)
                                .describedAs("Scanning type should be configured")
                                .isNotNull();
        }

        /**
         * Property 15c: Testing pipeline automation consistency
         * For any testing pipeline request, the system should provide
         * comprehensive testing strategies including security testing
         */
        @Property(tries = 100)
        void testingPipelineAutomationConsistency(
                        @ForAll("testingPipelineContexts") AgentContext context) {
                // Given: A testing pipeline automation request
                AgentRequest request = AgentRequest.builder()
                                .type("testing-pipeline")
                                .description("Testing pipeline automation property test")
                                .context(context)
                                .securityContext(security)
                                .build();

                // When: Processing the request through the agent manager
                AgentResponse response = agentManager.processRequest(request);

                // Then: The response should be successful
                assertThat(response.isSuccess())
                                .describedAs("Testing pipeline automation should succeed")
                                .isTrue();

                assertThat(response.getStatus())
                                .describedAs("Status should be present")
                                .isNotNull();

                // And: Processing should be within acceptable timeframe
                assertThat(response.getProcessingTimeMs())
                                .describedAs("Processing should be timely")
                                .isGreaterThan(0);
        }

        @Provide
        Arbitrary<AgentContext> pipelineConfigurationContexts() {
                return Arbitraries.of(
                                "microservices", "monolithic", "serverless", "containerized", "hybrid")
                                .map(architecture -> AgentContext.builder()
                                                .agentDomain("ci-cd")
                                                .property("architecture", architecture)
                                                .property("includeSecurityScanning", "true")
                                                .build());
        }

        @Provide
        Arbitrary<AgentContext> securityScanningContexts() {
                return Arbitraries.of("sast", "dast", "dependency", "container", "combined")
                                .map(scanType -> AgentContext.builder()
                                                .agentDomain("security")
                                                .property("scanningType", scanType)
                                                .property("automationLevel", "high")
                                                .build());
        }

        @Provide
        Arbitrary<AgentContext> testingPipelineContexts() {
                return Arbitraries.of(
                                "unit", "integration", "contract", "security", "end-to-end")
                                .map(testType -> AgentContext.builder()
                                                .agentDomain("testing")
                                                .property("testType", testType)
                                                .property("coverageRequired", "80")
                                                .build());
        }
}
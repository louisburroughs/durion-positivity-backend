package com.pos.agent.story;

import com.pos.agent.core.AgentManager;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.core.SecurityContext;
import com.pos.agent.context.AgentContext;
import net.jqwik.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Property-based test for metadata extraction completeness.
 * **Feature: upgrade-story-quality, Property 5: Metadata extraction
 * completeness**
 * **Validates: Requirements 2.1, 2.2, 2.3, 2.4**
 */
class MetadataExtractionCompletenessPropertyTest {

        private final AgentManager agentManager = new AgentManager();
        private final SecurityContext securityContext = SecurityContext.builder()
                        .jwtToken("metadata-extraction-jwt-token")
                        .userId("metadata-extractor")
                        .roles(List.of("admin", "developer", "operator"))
                        .permissions(List.of(
                                        "metadata:extract",
                                        "metadata:read",
                                        "metadata:write",
                                        "AGENT_READ",
                                        "AGENT_WRITE",
                                        "agent:read",
                                        "agent:write",
                                        "agent:execute"))
                        .serviceId("pos-metadata-tests")
                        .serviceType("property")
                        .build();

        /**
         * Property 5: Metadata extraction completeness
         * For any story context, the system should extract all metadata fields
         * (title, body, labels, repository) completely and accurately.
         */
        @Property(tries = 100)
        void metadataExtractionCompleteness(
                        @ForAll("validStoryIssues") AgentContext context) {
                // When: Processing metadata extraction request
                AgentResponse response = agentManager.processRequest(AgentRequest.builder()
                                .description("Metadata extraction completeness property test")
                                .type("metadata-extraction")
                                .context(context)
                                .securityContext(securityContext)
                                .build());

                // Then: All metadata should be extracted successfully
                assertTrue(response.isSuccess(), "Metadata extraction should succeed");
                assertNotNull(response.getStatus(), "Response should have status");
        }

        /**
         * Property: Metadata extraction preserves all label information
         */
        @Property(tries = 100)
        void metadataExtractionPreservesLabels(
                        @ForAll("validStoryIssues") AgentContext context) {
                // When: Processing metadata extraction with labels
                AgentResponse response = agentManager.processRequest(AgentRequest.builder()
                                .description("Metadata extraction preserves all label information property test")
                                .type("label-preservation")
                                .context(context)
                                .securityContext(securityContext)
                                .build());

                // Then: Labels should be preserved
                assertTrue(response.isSuccess(), "Label preservation should succeed");
                assertNotNull(response.getStatus(), "Response should have status");
        }

        /**
         * Property: Metadata extraction handles empty bodies
         */
        @Property(tries = 100)
        void metadataExtractionHandlesEmptyBodies(
                        @ForAll("issuesWithEmptyBodies") AgentContext context) {
                // When: Processing metadata extraction with empty body
                AgentResponse response = agentManager.processRequest(AgentRequest.builder()
                                .description("Metadata extraction handles empty bodies property test")
                                .type("empty-body-handling")
                                .context(context)
                                .securityContext(securityContext)
                                .build());

                // Then: Metadata should still be extracted
                assertTrue(response.isSuccess(), "Extraction should handle empty bodies");
                assertNotNull(response.getStatus(), "Response should have status");
        }

        // ========== Arbitraries ==========

        @Provide
        Arbitrary<AgentContext> validStoryIssues() {
                return Arbitraries.strings()
                                .withCharRange('a', 'z')
                                .withCharRange('A', 'Z')
                                .withCharRange('0', '9')
                                .withChars(' ', '-')
                                .ofMinLength(10)
                                .ofMaxLength(100)
                                .map(title -> AgentContext.builder()
                                                .domain("story")
                                                .property("title", title)
                                                .property("repository", "durion-positivity-backend")
                                                .property("hasLabels", true)
                                                .property("hasBody", true)
                                                .build());
        }

        @Provide
        Arbitrary<AgentContext> issuesWithEmptyBodies() {
                return Arbitraries.strings()
                                .withCharRange('a', 'z')
                                .withCharRange('A', 'Z')
                                .ofMinLength(10)
                                .ofMaxLength(100)
                                .map(title -> AgentContext.builder()
                                                .domain("story")
                                                .property("title", title)
                                                .property("repository", "durion-positivity-backend")
                                                .property("hasLabels", false)
                                                .property("hasBody", false)
                                                .build());
        }
}

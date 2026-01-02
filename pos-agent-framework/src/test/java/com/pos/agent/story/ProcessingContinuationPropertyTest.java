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
 * Property-based test for processing continuation on valid story requests.
 * **Feature: upgrade-story-quality, Property 4: Processing continuation on
 * valid stories**
 * **Validates: Requirements 1.5**
 */
class ProcessingContinuationPropertyTest {

        private final AgentManager agentManager = new AgentManager();
        private final SecurityContext securityContext = SecurityContext.builder()
                        .jwtToken("processing-continuation-jwt-token")
                        .userId("story-validator")
                        .roles(List.of("admin", "developer", "operator"))
                        .permissions(List.of(
                                        "story:validate",
                                        "story:read",
                                        "story:write",
                                        "AGENT_READ",
                                        "AGENT_WRITE",
                                        "agent:execute",
                                        "processing:continue"))
                        .serviceId("pos-story-tests")
                        .serviceType("property")
                        .build();

        /**
         * Property 4: Processing continuation on valid stories
         * For any story request that passes all activation conditions, the system
         * should
         * proceed to the transformation stage (indicated by successful response).
         */
        @Property(tries = 100)
        void processingContinuationOnValidStories(@ForAll("validStories") AgentContext context) {
                // When: Processing a valid story request
                AgentResponse response = agentManager.processRequest(AgentRequest.builder()
                                .description("Processing continuation on valid stories property test")
                                .type("story-continuation")
                                .context(context)
                                .securityContext(securityContext)
                                .build());

                // Then: Processing should continue successfully
                assertTrue(response.isSuccess(), "Valid story should pass processing");
                assertNotNull(response.getStatus(), "Response should have status");
        }

        /**
         * Property: All activation conditions must be met for processing to continue
         */
        @Property(tries = 100)
        void allActivationConditionsMustBeMet(@ForAll("storiesWithVariedValidity") AgentContext context) {
                // When: Processing the story request
                AgentResponse response = agentManager.processRequest(AgentRequest.builder()
                                .description("All activation conditions must be met property test")
                                .type("story-activation-check")
                                .context(context)
                                .securityContext(securityContext)
                                .build());

                // Then: Check activation conditions via response
                boolean isBackendStory = context.getProperty("isBackendStory") != null &&
                                (Boolean) context.getProperty("isBackendStory");
                boolean isValidContent = context.getProperty("isValidContent") != null &&
                                (Boolean) context.getProperty("isValidContent");

                boolean shouldContinue = isBackendStory && isValidContent;

                // And: Processing should continue only if all conditions are met
                assertTrue(response.isSuccess() == shouldContinue,
                                "Processing should continue only when all conditions met");
        }

        /**
         * Property: Valid stories with different content variations all pass
         */
        @Property(tries = 100)
        void validStoriesWithVariationsPass(@ForAll("validStoriesWithVariations") AgentContext context) {
                // When: Processing a valid story with content variations
                AgentResponse response = agentManager.processRequest(AgentRequest.builder()
                                .description("Valid stories with different content variations property test")
                                .type("story-variations")
                                .context(context)
                                .securityContext(securityContext)
                                .build());

                // Then: Should pass regardless of content variations
                assertTrue(response.isSuccess(), "Valid story with variations should pass");
                assertNotNull(response.getStatus(), "Response should have status");
        }

        /**
         * Property: Processing continuation is deterministic
         */
        @Property(tries = 100)
        void processingContinuationIsDeterministic(@ForAll("validStories") AgentContext context) {
                // When: Processing the same story multiple times
                AgentResponse result1 = agentManager.processRequest(AgentRequest.builder()
                                .description("Processing continuation is deterministic property test - request 1")
                                .type("story-determinism")
                                .context(context)
                                .securityContext(securityContext)
                                .build());

                AgentResponse result2 = agentManager.processRequest(AgentRequest.builder()
                                .description("Processing continuation is deterministic property test - request 2")
                                .type("story-determinism")
                                .context(context)
                                .securityContext(securityContext)
                                .build());

                AgentResponse result3 = agentManager.processRequest(AgentRequest.builder()
                                .description("Processing continuation is deterministic property test - request 3")
                                .type("story-determinism")
                                .context(context)
                                .securityContext(securityContext)
                                .build());

                // Then: Results should be identical
                assertTrue(result1.isSuccess() == result2.isSuccess() && result2.isSuccess() == result3.isSuccess(),
                                "Processing should be deterministic");
        }

        // ========== Arbitraries ==========

        @Provide
        Arbitrary<AgentContext> validStories() {
                return Arbitraries.of(
                                "User Authentication",
                                "Order Processing",
                                "Payment Integration",
                                "Inventory Management")
                                .map(storyName -> AgentContext.builder()
                                                .agentDomain("story")
                                                .property("storyName", storyName)
                                                .property("isBackendStory", true)
                                                .property("isValidContent", true)
                                                .build());
        }

        @Provide
        Arbitrary<AgentContext> storiesWithVariedValidity() {
                return Arbitraries.oneOf(
                                validStories(),
                                invalidBackendStories(),
                                invalidContentStories());
        }

        @Provide
        Arbitrary<AgentContext> validStoriesWithVariations() {
                return Arbitraries.of(
                                "Short Story",
                                "Very Long Story Title With Many Words And Details About The Feature Implementation",
                                "Story With Numbers 123",
                                "Story-With-Dashes-And-Details")
                                .map(storyName -> AgentContext.builder()
                                                .agentDomain("story")
                                                .property("storyName", storyName)
                                                .property("contentLength", storyName.length())
                                                .property("isBackendStory", true)
                                                .property("isValidContent", true)
                                                .build());
        }

        @Provide
        Arbitrary<AgentContext> invalidBackendStories() {
                return Arbitraries.of("Frontend", "Database", "DevOps")
                                .map(storyType -> AgentContext.builder()
                                                .agentDomain("story")
                                                .property("storyName", storyType + " Story")
                                                .property("isBackendStory", false)
                                                .property("isValidContent", true)
                                                .build());
        }

        @Provide
        Arbitrary<AgentContext> invalidContentStories() {
                return Arbitraries.of(
                                "Incomplete",
                                "X",
                                "Task: Fix bug")
                                .map(content -> AgentContext.builder()
                                                .agentDomain("story")
                                                .property("storyName", content)
                                                .property("isBackendStory", true)
                                                .property("isValidContent", false)
                                                .build());
        }
}

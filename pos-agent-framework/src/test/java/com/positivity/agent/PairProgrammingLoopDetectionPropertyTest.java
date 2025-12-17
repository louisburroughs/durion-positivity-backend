package com.positivity.agent;

import com.positivity.agent.impl.PairProgrammingNavigatorAgent;
import net.jqwik.api.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **Feature: agent-structure, Property 13: Pair programming loop detection**
 * 
 * Property-based tests for PairProgrammingNavigatorAgent loop detection
 * capabilities.
 * Validates Requirements REQ-011.2, REQ-011.4
 * 
 * Property 13: For any implementation session with stalled progress,
 * the system should detect loops and provide mandatory stop-phrase interruption
 */
public class PairProgrammingLoopDetectionPropertyTest {

        private PairProgrammingNavigatorAgent getAgent() {
                return new PairProgrammingNavigatorAgent();
        }

        /**
         * **Feature: agent-structure, Property 13: Pair programming loop detection**
         * 
         * Core property test: For any implementation context indicating stalled
         * progress or loops,
         * the agent should detect the loop and provide mandatory stop-phrase
         * interruption.
         */
        @Property(tries = 100)
        void shouldDetectLoopsAndProvideStopPhraseInterruption(
                        @ForAll("stalledImplementationContexts") String stalledContext) {

                // Given: A consultation request with stalled implementation context
                AgentConsultationRequest request = AgentConsultationRequest.create(
                                "collaboration",
                                stalledContext,
                                Map.of("requestType", "loop-detection", "requester", "Spring Boot developer"));

                // When: Consulting the pair programming navigator agent
                AgentGuidanceResponse response = getAgent().provideGuidance(request).join();

                // Then: The response should be successful
                assertThat(response.status())
                                .as("Agent should successfully detect loops in stalled contexts")
                                .isEqualTo(AgentGuidanceResponse.ResponseStatus.SUCCESS);

                // And: The response should contain a mandatory stop-phrase
                String guidance = response.guidance();
                boolean containsStopPhrase = containsMandatoryStopPhrase(guidance);
                assertThat(containsStopPhrase)
                                .as("Response should contain a mandatory stop-phrase for loop interruption")
                                .isTrue();

                // And: The response should provide corrective actions
                assertThat(guidance)
                                .as("Response should provide immediate corrective actions")
                                .containsAnyOf("STOP", "Immediate Actions", "Loop Detection Alert");

                // And: The response should have high confidence
                assertThat(response.confidence())
                                .as("Loop detection should have high confidence")
                                .isGreaterThan(0.8);
        }

        /**
         * Property test: For any architectural drift context, the agent should enforce
         * design constraints
         */
        @Property(tries = 100)
        void shouldDetectArchitecturalDriftAndEnforceConstraints(
                        @ForAll("architecturalDriftContexts") String driftContext) {

                // Given: A consultation request with architectural drift indicators
                AgentConsultationRequest request = AgentConsultationRequest.create(
                                "collaboration",
                                driftContext,
                                Map.of("requestType", "architectural-drift", "requester",
                                                "Implementation deviating from design"));

                // When: Consulting the agent
                AgentGuidanceResponse response = getAgent().provideGuidance(request).join();

                // Then: The response should be successful
                assertThat(response.status())
                                .as("Agent should successfully detect architectural drift")
                                .isEqualTo(AgentGuidanceResponse.ResponseStatus.SUCCESS);

                // And: The response should address design constraint enforcement
                String guidance = response.guidance();
                assertThat(guidance)
                                .as("Response should address architectural drift")
                                .containsAnyOf("Architectural Drift", "Design Constraint", "Domain Boundaries");

                // And: The response should provide corrective actions
                assertThat(guidance)
                                .as("Response should provide corrective actions for drift")
                                .containsAnyOf("Review", "Identify", "Implement proper patterns");
        }

        /**
         * Property test: For any scope creep context, the agent should provide
         * simplification guidance
         */
        @Property(tries = 100)
        void shouldDetectScopeCreepAndProvideSimplificationGuidance(
                        @ForAll("scopeCreepContexts") String scopeContext) {

                // Given: A consultation request with scope creep indicators
                AgentConsultationRequest request = AgentConsultationRequest.create(
                                "collaboration",
                                scopeContext,
                                Map.of("requestType", "scope-creep", "requester",
                                                "Implementation expanding beyond requirements"));

                // When: Consulting the agent
                AgentGuidanceResponse response = getAgent().provideGuidance(request).join();

                // Then: The response should be successful
                assertThat(response.status())
                                .as("Agent should successfully detect scope creep")
                                .isEqualTo(AgentGuidanceResponse.ResponseStatus.SUCCESS);

                // And: The response should provide simplification guidance
                String guidance = response.guidance();
                assertThat(guidance)
                                .as("Response should provide simplification guidance")
                                .containsAnyOf("Scope Creep", "Simplification", "Focus on Requirements");

                // And: The response should recommend scope management
                assertThat(guidance)
                                .as("Response should recommend scope management actions")
                                .containsAnyOf("Remove extra features", "Minimum Viable", "Review requirements");
        }

        /**
         * Property test: For any valid collaboration request, the agent should provide
         * appropriate guidance
         */
        @Property(tries = 100)
        void shouldProvideCollaborationGuidanceForValidRequests(
                        @ForAll("validCollaborationContexts") String collaborationContext) {

                // Given: A valid collaboration consultation request
                AgentConsultationRequest request = AgentConsultationRequest.create(
                                "collaboration",
                                collaborationContext,
                                Map.of("requestType", "collaboration-guidance", "requester",
                                                "Pair programming navigation needed"));

                // When: Consulting the agent
                AgentGuidanceResponse response = getAgent().provideGuidance(request).join();

                // Then: The response should be successful
                assertThat(response.status())
                                .as("Agent should provide guidance for valid collaboration requests")
                                .isEqualTo(AgentGuidanceResponse.ResponseStatus.SUCCESS);

                // And: The response should contain collaboration guidelines
                String guidance = response.guidance();
                assertThat(guidance)
                                .as("Response should contain collaboration guidelines")
                                .containsAnyOf("Collaboration Guidelines", "State Intent", "Propose Approach");

                // And: The response should have recommendations
                assertThat(response.recommendations())
                                .as("Response should include actionable recommendations")
                                .isNotEmpty();
        }

        /**
         * Unit test: Verify mandatory stop-phrases are properly defined
         */
        @Test
        void shouldHaveMandatoryStopPhrasesForLoopDetection() {
                // Given: Known loop contexts
                List<String> loopContexts = Arrays.asList(
                                "We are implementing the same entity changes for the third time",
                                "The service layer keeps getting refactored without progress",
                                "Business logic is being moved to the screen layer again",
                                "We are crossing domain boundaries for convenience",
                                "Framework features are being used without clear purpose");

                // When & Then: Each loop context should trigger appropriate stop-phrase
                for (String context : loopContexts) {
                        AgentConsultationRequest request = AgentConsultationRequest.create(
                                        "collaboration", context, Map.of("requestType", "loop-detection"));

                        AgentGuidanceResponse response = getAgent().provideGuidance(request).join();

                        assertThat(response.status())
                                        .as("Should detect loop in context: " + context)
                                        .isEqualTo(AgentGuidanceResponse.ResponseStatus.SUCCESS);

                        assertThat(containsMandatoryStopPhrase(response.guidance()))
                                        .as("Should contain stop-phrase for context: " + context)
                                        .isTrue();
                }
        }

        /**
         * Unit test: Verify performance requirements are met
         */
        @Test
        void shouldMeetPerformanceRequirements() {
                // Given: A standard consultation request
                AgentConsultationRequest request = AgentConsultationRequest.create(
                                "collaboration",
                                "Standard implementation guidance needed",
                                Map.of("requestType", "performance-test", "requester", "Performance validation"));

                // When: Measuring response time
                long startTime = System.currentTimeMillis();
                AgentGuidanceResponse response = getAgent().provideGuidance(request).join();
                long responseTime = System.currentTimeMillis() - startTime;

                // Then: Response should be within performance requirements
                assertThat(response.status())
                                .as("Agent should respond successfully")
                                .isEqualTo(AgentGuidanceResponse.ResponseStatus.SUCCESS);

                assertThat(responseTime)
                                .as("Response time should be under 5 seconds")
                                .isLessThan(5000);
        }

        // Generators for property-based testing

        @Provide
        Arbitrary<String> stalledImplementationContexts() {
                return Arbitraries.oneOf(
                                // Entity churn patterns
                                Arbitraries.of(
                                                "We are modifying the entity model for the third time without clear progress",
                                                "The data model keeps changing but the behavior is not improving",
                                                "Entities are being redesigned to compensate for unclear business logic",
                                                "We are repeatedly refactoring the same entity relationships"),
                                // Service explosion patterns
                                Arbitraries.of(
                                                "We are creating multiple services to avoid making a design decision",
                                                "Services are being split without clear policy boundaries",
                                                "We are wrapping CRUD operations without adding business value",
                                                "The service layer is expanding but functionality is not improving"),
                                // Decision churn patterns
                                Arbitraries.of(
                                                "We are stuck choosing between multiple implementation options",
                                                "The same design decision is being revisited repeatedly",
                                                "Progress has stalled due to too many architectural choices",
                                                "We are re-solving a problem that hasn't fundamentally changed"));
        }

        @Provide
        Arbitrary<String> architecturalDriftContexts() {
                return Arbitraries.oneOf(
                                Arbitraries.of(
                                                "Let's take a shortcut and bypass the API Gateway for this service",
                                                "We can create a quick fix by directly accessing another service's database",
                                                "This temporary workaround will violate domain boundaries but save time",
                                                "We should ignore the service contract and make a direct call",
                                                "Let's break the established pattern just for this feature"),
                                Arbitraries.of(
                                                "We can create tight coupling between services for better performance",
                                                "Let's add a circular dependency to solve this integration issue",
                                                "We should bypass the established integration patterns",
                                                "This hack will work around the architectural constraints"));
        }

        @Provide
        Arbitrary<String> scopeCreepContexts() {
                return Arbitraries.oneOf(
                                Arbitraries.of(
                                                "While we're implementing this feature, we might as well add extra validation",
                                                "Let's also add some nice-to-have enhancements to make it more flexible",
                                                "We should future-proof this implementation with additional abstractions",
                                                "Let's add some optimization while we're working on this code",
                                                "We can make this more generic to handle future requirements"),
                                Arbitraries.of(
                                                "This would be a good time to add that enhancement we discussed",
                                                "Let's make this extensible for potential future use cases",
                                                "We should add extra features to make it more complete",
                                                "While implementing this, let's also improve the related functionality"));
        }

        @Provide
        Arbitrary<String> validCollaborationContexts() {
                return Arbitraries.oneOf(
                                Arbitraries.of(
                                                "Need guidance on implementing user authentication service",
                                                "Working on order processing workflow, need collaboration support",
                                                "Implementing inventory management features, seeking navigation",
                                                "Developing payment integration, need pair programming guidance"),
                                Arbitraries.strings()
                                                .withCharRange('a', 'z')
                                                .withCharRange('A', 'Z')
                                                .withCharRange(' ', ' ')
                                                .ofMinLength(10)
                                                .ofMaxLength(100)
                                                .filter(s -> !containsLoopIndicators(s) &&
                                                                !containsDriftIndicators(s) &&
                                                                !containsScopeCreepIndicators(s)));
        }

        // Helper methods

        private boolean containsMandatoryStopPhrase(String guidance) {
                if (guidance == null)
                        return false;

                List<String> stopPhrases = Arrays.asList(
                                "We are looping.",
                                "This is the third pass on the same solution.",
                                "We are re-solving a problem that hasn't changed.",
                                "We are churning entities.",
                                "The data model is moving, but the behavior is not.",
                                "We're redesigning entities to compensate for unclear behavior.",
                                "We are creating services to avoid making a decision.",
                                "This is service sprawl.",
                                "We are wrapping CRUD without adding policy.",
                                "Business logic is leaking into the screen layer.",
                                "Screens are doing policy work.",
                                "This logic does not belong in a transition.",
                                "We are crossing a domain boundary.",
                                "This creates hidden coupling between domains.",
                                "This violates the service contract boundary.",
                                "We are leaning on the framework instead of modeling the problem.",
                                "This is a framework feature in search of a use case.",
                                "Framework is being used as a crutch here.",
                                "Momentum has stalled.",
                                "We are stuck in decision churn.",
                                "We need to collapse options.");

                return stopPhrases.stream().anyMatch(guidance::contains);
        }

        private boolean containsLoopIndicators(String context) {
                String lower = context.toLowerCase();
                return lower.contains("third time") || lower.contains("again") ||
                                lower.contains("repeatedly") || lower.contains("same approach");
        }

        private boolean containsDriftIndicators(String context) {
                String lower = context.toLowerCase();
                return lower.contains("shortcut") || lower.contains("bypass") ||
                                lower.contains("violate") || lower.contains("hack");
        }

        private boolean containsScopeCreepIndicators(String context) {
                String lower = context.toLowerCase();
                return lower.contains("also add") || lower.contains("might as well") ||
                                lower.contains("future proof") || lower.contains("enhancement");
        }
}
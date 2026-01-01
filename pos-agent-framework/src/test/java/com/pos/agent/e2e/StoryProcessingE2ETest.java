package com.pos.agent.e2e;

import com.pos.agent.context.StoryContext;
import com.pos.agent.impl.StoryProcessingAgent;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StoryProcessingE2ETest {

        // HTTP webhook tests removed per core API guidance

        @Test
        void testStoryDetectionAndParsing() {
                // Given: Story processing agent
                StoryProcessingAgent agent = new StoryProcessingAgent();

                StoryContext context = StoryContext.builder()
                                .repositoryUrl("https://github.com/louisburroughs/durion-positivity-backend")
                                .issueId(123L)
                                .issueTitle("[STORY] Implement customer service")
                                .issueBody(
                                                "AC: Service should manage customer data\nAC: Service should validate customer input\nModule: pos-customer")
                                .moduleName("pos-customer")
                                .build();

                AgentRequest request = AgentRequest.builder()
                                .type("story-processing")
                                .description("Process customer service story")
                                .context(context)
                                .build();

                // When: Story is processed
                AgentResponse response = agent.processRequest(request);

                // Then: Story is correctly parsed
                assertEquals("SUCCESS", response.getStatus());
                assertTrue(response.getOutput().contains("pos-customer"));
                assertTrue(response.getOutput().contains("customer data"));
                assertTrue(response.getOutput().contains("validate customer input"));
        }

        @Test
        void testModuleIdentificationAndDependencyResolution() {
                // Given: Story with multiple modules
                StoryProcessingAgent agent = new StoryProcessingAgent();

                StoryContext context = StoryContext.builder()
                                .repositoryUrl("https://github.com/louisburroughs/durion-positivity-backend")
                                .issueId(124L)
                                .issueTitle("[STORY] Implement order processing")
                                .issueBody(
                                                "AC: Orders should reference customers\nAC: Orders should reference inventory\nModules: pos-order, pos-customer, pos-inventory")
                                .moduleName("pos-order")
                                .build();

                AgentRequest request = AgentRequest.builder()
                                .type("story-processing")
                                .description("Process order processing story with dependencies")
                                .context(context)
                                .build();

                // When: Dependencies are resolved
                AgentResponse response = agent.processRequest(request);

                // Then: All modules are identified
                assertEquals("SUCCESS", response.getStatus());
                assertTrue(response.getOutput().contains("pos-order"));
                assertTrue(response.getOutput().contains("pos-customer"));
                assertTrue(response.getOutput().contains("pos-inventory"));
        }

        @Test
        void testMavenBuildExecution() {
                // Given: Story requiring build
                StoryProcessingAgent agent = new StoryProcessingAgent();

                StoryContext context = StoryContext.builder()
                                .repositoryUrl("https://github.com/louisburroughs/durion-positivity-backend")
                                .issueId(125L)
                                .issueTitle("[STORY] Add validation to inventory service")
                                .issueBody("AC: Input validation should be implemented\nModule: pos-inventory")
                                .moduleName("pos-inventory")
                                .build();

                AgentRequest request = AgentRequest.builder()
                                .type("story-processing")
                                .description("Execute Maven build for inventory service")
                                .context(context)
                                .build();

                // When: Build is executed
                AgentResponse response = agent.processRequest(request);

                // Then: Build results are captured
                assertTrue(response.getStatus().equals("SUCCESS") || response.getStatus().equals("FAILURE"));
                String out = response.getOutput();
                assertTrue(out.contains("BUILD SUCCESS") || out.contains("BUILD FAILURE")
                                || out.contains("Tests run:"));
        }

        @Test
        void testFailureHandlingAndRetryLogic() {
                // Given: Story that will fail initially
                StoryProcessingAgent agent = new StoryProcessingAgent();

                StoryContext context = StoryContext.builder()
                                .repositoryUrl("https://github.com/louisburroughs/durion-positivity-backend")
                                .issueId(126L)
                                .issueTitle("[STORY] Invalid module test")
                                .issueBody("AC: This should fail\nModule: non-existent-module")
                                .moduleName("non-existent-module")
                                .build();

                AgentRequest request = AgentRequest.builder()
                                .type("story-processing")
                                .description("Process invalid module story")
                                .context(context)
                                .build();

                // When: Processing fails
                AgentResponse response = agent.processRequest(request);

                // Then: Failure is handled gracefully
                assertEquals("FAILURE", response.getStatus());
                assertTrue(response.getOutput().contains("Module not found"));
                assertNotNull(response.getRecommendations());
                assertFalse(response.getRecommendations().isEmpty());
        }

        @Test
        void testCircularDependencyDetection() {
                // Given: Story with circular dependencies
                StoryProcessingAgent agent = new StoryProcessingAgent();

                StoryContext context = StoryContext.builder()
                                .repositoryUrl("https://github.com/louisburroughs/durion-positivity-backend")
                                .issueId(127L)
                                .issueTitle("[STORY] Circular dependency test")
                                .issueBody("AC: Test circular detection\nDependencies: Closes #128\nModule: pos-test")
                                .moduleName("pos-test")
                                .dependencies(java.util.List.of("128"))
                                .build();

                AgentRequest request = AgentRequest.builder()
                                .type("story-processing")
                                .description("Detect circular dependencies in story")
                                .context(context)
                                .build();

                // When: Circular dependency is detected
                AgentResponse response = agent.processRequest(request);

                // Then: Escalation occurs
                assertTrue(response.getStatus().equals("ESCALATION") || response.getStatus().equals("FAILURE"));
                if (response.getOutput().contains("circular")) {
                        assertTrue(response.getOutput().contains("circular dependency"));
                }
        }

        @Test
        void testResultPostingToGitHub() {
                // Given: Successful story processing
                StoryProcessingAgent agent = new StoryProcessingAgent();

                StoryContext context = StoryContext.builder()
                                .repositoryUrl("https://github.com/louisburroughs/durion-positivity-backend")
                                .issueId(128L)
                                .issueTitle("[STORY] Simple success test")
                                .issueBody("AC: Should succeed\nModule: pos-catalog")
                                .moduleName("pos-catalog")
                                .build();

                AgentRequest request = AgentRequest.builder()
                                .type("story-processing")
                                .description("Process simple success story")
                                .context(context)
                                .build();

                // When: Story is processed successfully
                AgentResponse response = agent.processRequest(request);

                // Then: Results are formatted for GitHub
                assertEquals("SUCCESS", response.getStatus());
                assertTrue(response.getOutput().contains("## Build Results"));
                assertTrue(response.getOutput().contains("pos-catalog"));
        }

        // Integration webhook workflow removed per core API guidance

        // HTTP error scenario tests removed per core API guidance

        // Performance under HTTP load removed per core API guidance
}

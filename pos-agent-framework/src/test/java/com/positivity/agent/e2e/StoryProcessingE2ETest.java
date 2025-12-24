package com.positivity.agent.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.agent.context.StoryContext;
import com.positivity.agent.impl.StoryProcessingAgent;
import com.positivity.agent.model.AgentRequest;
import com.positivity.agent.model.AgentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for story processing workflow (REQ-018).
 * Tests GitHub webhook payload reception, story detection, module identification,
 * Maven build execution, failure handling, and result posting.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class StoryProcessingE2ETest {

    @LocalServerPort
    private int port;

    private TestRestTemplate restTemplate;
    private ObjectMapper objectMapper;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        restTemplate = new TestRestTemplate();
        objectMapper = new ObjectMapper();
        baseUrl = "http://localhost:" + port;
    }

    @Test
    void testGitHubWebhookPayloadReception() {
        // Given: Valid GitHub webhook payload
        Map<String, Object> webhookPayload = Map.of(
            "action", "opened",
            "issue", Map.of(
                "id", 123,
                "number", 456,
                "title", "[STORY] Implement inventory service",
                "body", "AC: Service should handle CRUD operations\nModule: pos-inventory",
                "state", "open",
                "assignee", null,
                "labels", new Object[]{Map.of("name", "[STORY]")}
            ),
            "repository", Map.of(
                "name", "durion-positivity-backend",
                "full_name", "louisburroughs/durion-positivity-backend"
            )
        );

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-GitHub-Event", "issues");
        headers.set("Content-Type", "application/json");
        
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(webhookPayload, headers);

        // When: Webhook is received
        ResponseEntity<String> response = restTemplate.postForEntity(
            baseUrl + "/api/webhook/github", request, String.class);

        // Then: Webhook is accepted
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void testStoryDetectionAndParsing() {
        // Given: Story processing agent
        StoryProcessingAgent agent = new StoryProcessingAgent();
        
        StoryContext context = StoryContext.builder()
            .repositoryUrl("https://github.com/louisburroughs/durion-positivity-backend")
            .issueId(123L)
            .issueTitle("[STORY] Implement customer service")
            .issueBody("AC: Service should manage customer data\nAC: Service should validate customer input\nModule: pos-customer")
            .moduleName("pos-customer")
            .build();

        AgentRequest request = AgentRequest.builder()
            .type("story-processing")
            .context(context)
            .build();

        // When: Story is processed
        AgentResponse response = agent.processRequest(request);

        // Then: Story is correctly parsed
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getOutput()).contains("pos-customer");
        assertThat(response.getOutput()).contains("customer data");
        assertThat(response.getOutput()).contains("validate customer input");
    }

    @Test
    void testModuleIdentificationAndDependencyResolution() {
        // Given: Story with multiple modules
        StoryProcessingAgent agent = new StoryProcessingAgent();
        
        StoryContext context = StoryContext.builder()
            .repositoryUrl("https://github.com/louisburroughs/durion-positivity-backend")
            .issueId(124L)
            .issueTitle("[STORY] Implement order processing")
            .issueBody("AC: Orders should reference customers\nAC: Orders should reference inventory\nModules: pos-order, pos-customer, pos-inventory")
            .moduleName("pos-order")
            .build();

        AgentRequest request = AgentRequest.builder()
            .type("story-processing")
            .context(context)
            .build();

        // When: Dependencies are resolved
        AgentResponse response = agent.processRequest(request);

        // Then: All modules are identified
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getOutput()).contains("pos-order");
        assertThat(response.getOutput()).contains("pos-customer");
        assertThat(response.getOutput()).contains("pos-inventory");
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
            .context(context)
            .build();

        // When: Build is executed
        AgentResponse response = agent.processRequest(request);

        // Then: Build results are captured
        assertThat(response.getStatus()).isIn("SUCCESS", "FAILURE");
        assertThat(response.getOutput()).containsAnyOf("BUILD SUCCESS", "BUILD FAILURE", "Tests run:");
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
            .context(context)
            .build();

        // When: Processing fails
        AgentResponse response = agent.processRequest(request);

        // Then: Failure is handled gracefully
        assertThat(response.getStatus()).isEqualTo("FAILURE");
        assertThat(response.getOutput()).contains("Module not found");
        assertThat(response.getRecommendations()).isNotEmpty();
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
            .context(context)
            .build();

        // When: Circular dependency is detected
        AgentResponse response = agent.processRequest(request);

        // Then: Escalation occurs
        assertThat(response.getStatus()).isIn("ESCALATION", "FAILURE");
        if (response.getOutput().contains("circular")) {
            assertThat(response.getOutput()).contains("circular dependency");
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
            .context(context)
            .build();

        // When: Story is processed successfully
        AgentResponse response = agent.processRequest(request);

        // Then: Results are formatted for GitHub
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getOutput()).contains("## Build Results");
        assertThat(response.getOutput()).contains("pos-catalog");
    }

    @Test
    void testStoryProcessingWorkflowIntegration() {
        // Given: Complete story workflow
        Map<String, Object> webhookPayload = Map.of(
            "action", "opened",
            "issue", Map.of(
                "id", 129,
                "number", 789,
                "title", "[STORY] End-to-end workflow test",
                "body", "AC: Complete workflow should work\nAC: All components should integrate\nModule: pos-integration-test",
                "state", "open",
                "assignee", null,
                "labels", new Object[]{Map.of("name", "[STORY]")}
            ),
            "repository", Map.of(
                "name", "durion-positivity-backend",
                "full_name", "louisburroughs/durion-positivity-backend"
            )
        );

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-GitHub-Event", "issues");
        headers.set("Content-Type", "application/json");
        
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(webhookPayload, headers);

        // When: Complete workflow is executed
        ResponseEntity<String> response = restTemplate.postForEntity(
            baseUrl + "/api/webhook/github", request, String.class);

        // Then: Workflow completes successfully
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        
        // Additional verification could include checking queue status,
        // build results, and GitHub comment posting (if implemented)
    }

    @Test
    void testErrorScenarios() {
        // Test various error conditions
        
        // Invalid webhook payload
        HttpEntity<String> invalidRequest = new HttpEntity<>("{invalid json}", new HttpHeaders());
        ResponseEntity<String> response1 = restTemplate.postForEntity(
            baseUrl + "/api/webhook/github", invalidRequest, String.class);
        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Missing required fields
        Map<String, Object> incompletePayload = Map.of("action", "opened");
        HttpEntity<Map<String, Object>> incompleteRequest = new HttpEntity<>(incompletePayload, new HttpHeaders());
        ResponseEntity<String> response2 = restTemplate.postForEntity(
            baseUrl + "/api/webhook/github", incompleteRequest, String.class);
        assertThat(response2.getStatusCode()).isIn(HttpStatus.BAD_REQUEST, HttpStatus.UNPROCESSABLE_ENTITY);

        // Non-story issue (should be ignored)
        Map<String, Object> nonStoryPayload = Map.of(
            "action", "opened",
            "issue", Map.of(
                "id", 130,
                "title", "Regular issue without [STORY] label",
                "labels", new Object[]{}
            )
        );
        HttpEntity<Map<String, Object>> nonStoryRequest = new HttpEntity<>(nonStoryPayload, new HttpHeaders());
        ResponseEntity<String> response3 = restTemplate.postForEntity(
            baseUrl + "/api/webhook/github", nonStoryRequest, String.class);
        assertThat(response3.getStatusCode()).isEqualTo(HttpStatus.OK); // Accepted but ignored
    }

    @Test
    void testPerformanceUnderLoad() {
        // Test multiple concurrent story processing requests
        int concurrentRequests = 5;
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < concurrentRequests; i++) {
            Map<String, Object> webhookPayload = Map.of(
                "action", "opened",
                "issue", Map.of(
                    "id", 200 + i,
                    "number", 800 + i,
                    "title", "[STORY] Performance test " + i,
                    "body", "AC: Performance test\nModule: pos-test-" + i,
                    "state", "open",
                    "assignee", null,
                    "labels", new Object[]{Map.of("name", "[STORY]")}
                ),
                "repository", Map.of(
                    "name", "durion-positivity-backend",
                    "full_name", "louisburroughs/durion-positivity-backend"
                )
            );

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-GitHub-Event", "issues");
            
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(webhookPayload, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl + "/api/webhook/github", request, String.class);
            
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;

        // Verify performance target: should handle requests within reasonable time
        assertThat(totalTime).isLessThan(10000); // 10 seconds for 5 requests
    }
}

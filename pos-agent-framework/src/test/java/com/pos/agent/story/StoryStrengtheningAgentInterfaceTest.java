package com.pos.agent.story;

import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.core.AgentStatus;
import com.pos.agent.story.analysis.RequirementsAnalyzer;
import com.pos.agent.story.config.StoryConfiguration;
import com.pos.agent.story.loop.LoopDetectionResult;
import com.pos.agent.story.loop.LoopDetector;
import com.pos.agent.story.loop.ProcessingContext;
import com.pos.agent.story.models.*;
import com.pos.agent.story.output.OutputGenerator;
import com.pos.agent.story.parsing.IssueParser;
import com.pos.agent.story.transformation.RequirementsTransformer;
import com.pos.agent.story.validation.IssueValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for StoryStrengtheningAgent's implementation of the Agent interface.
 * Tests the doProcessRequest() method and integration with AbstractAgent.
 * 
 * Requirements: Agent interface compliance
 */
class StoryStrengtheningAgentInterfaceTest {

    private StoryStrengtheningAgent agent;
    private AgentRequest.Builder validRequestBuilder;

    @BeforeEach
    void setUp() {
        // Create a minimal valid agent with fake implementations
        IssueValidator validator = new FakeIssueValidator(true);
        IssueParser parser = new FakeIssueParser();
        RequirementsAnalyzer analyzer = new FakeRequirementsAnalyzer();
        RequirementsTransformer transformer = new FakeRequirementsTransformer();
        OutputGenerator outputGenerator = new FakeOutputGenerator();
        LoopDetector loopDetector = new FakeLoopDetector(false);
        StoryConfiguration config = StoryConfiguration.builder().build();

        agent = new StoryStrengtheningAgent(
                validator, parser, analyzer, transformer, outputGenerator, loopDetector, config);

        // Create a valid agent request builder
        Map<String, Object> contextMap = new HashMap<>();
        contextMap.put("repo", "test-repo");
        contextMap.put("body", "Test issue body");

        validRequestBuilder = AgentRequest.builder()
                .description("Test story issue")
                .type("test-type")
                .context(contextMap);
    }

    /**
     * Test that processRequest returns a valid AgentResponse
     */
    @Test
    void testProcessRequest_ReturnsAgentResponse() {
        AgentRequest request = validRequestBuilder.build();
        AgentResponse response = agent.processRequest(request);

        assertNotNull(response, "Response should not be null");
        assertNotNull(response.getStatus(), "Status should not be null");
        assertTrue(response.isSuccess(), "Response should indicate success");
    }

    /**
     * Test that successful processing returns SUCCESS status
     */
    @Test
    void testProcessRequest_SuccessfulProcessing() {
        AgentRequest request = validRequestBuilder.build();
        AgentResponse response = agent.processRequest(request);

        assertEquals(AgentStatus.SUCCESS, response.getStatusEnum(), "Status should be SUCCESS");
        assertNotNull(response.getOutput(), "Output should be present");
        assertNull(response.getErrorMessage(), "Error message should be null for success");
        assertTrue(response.getConfidence() > 0, "Confidence should be positive for success");
    }

    /**
     * Test that validation errors return FAILURE status
     */
    @Test
    void testProcessRequest_ValidationError_EmptyDescription() {
        AgentRequest invalidRequest = AgentRequest.builder()
                .description("") // Empty description
                .type("test-type").context(new HashMap<>()).build();

        AgentResponse response = agent.processRequest(invalidRequest);

        assertEquals(AgentStatus.FAILURE, response.getStatusEnum(), "Status should be FAILURE");
        assertFalse(response.isSuccess(), "Response should indicate failure");
        assertNotNull(response.getErrorMessage(), "Error message should be present");
    }

    /**
     * Test that processing result with loop detection returns failure
     * This test verifies that when doProcessRequest delegates to processIssue,
     * loop detection is properly handled and returned via the Agent interface.
     */
    @Test
    void testProcessRequest_LoopDetection_StopsProcessing() {
        // The loop detection functionality is thoroughly tested in
        // StoryStrengtheningAgentLoopIntegrationTest
        // This test just verifies that the Agent interface properly wraps loop
        // detection results

        // Create agent with all components
        IssueValidator validator = new FakeIssueValidator(true);
        IssueParser parser = new FakeIssueParser();
        RequirementsAnalyzer analyzer = new FakeRequirementsAnalyzer();
        RequirementsTransformer transformer = new FakeRequirementsTransformer();
        OutputGenerator outputGenerator = new FakeOutputGenerator();
        // Always detect loop - the checkpoint logic is tested in integration tests
        LoopDetector loopDetector = new AlwaysLoopDetector();
        StoryConfiguration config = StoryConfiguration.builder().build();

        StoryStrengtheningAgent loopAgent = new StoryStrengtheningAgent(
                validator, parser, analyzer, transformer, outputGenerator, loopDetector, config);

        AgentRequest request = validRequestBuilder.build();
        AgentResponse response = loopAgent.processRequest(request);

        assertEquals(AgentStatus.FAILURE, response.getStatusEnum(), "Status should be FAILURE when loop detected");
        assertFalse(response.isSuccess(), "Response should indicate failure");
        assertNotNull(response.getOutput(), "Output should be present");
        // The output should contain the stop phrase from loop detection
        assertNotNull(response.getErrorMessage(), "Error message should be set for failures");
    }

    /**
     * Test that response includes recommendations
     */
    @Test
    void testProcessRequest_SuccessfulResponse_IncludesRecommendations() {
        AgentRequest request = validRequestBuilder.build();
        AgentResponse response = agent.processRequest(request);

        assertNotNull(response.getRecommendations(), "Recommendations should be present");
        assertTrue(response.getRecommendations().size() > 0, "Should have at least one recommendation");
    }

    /**
     * Fake implementation of IssueValidator for testing
     */
    private static class FakeIssueValidator implements IssueValidator {
        private final boolean valid;

        FakeIssueValidator(boolean valid) {
            this.valid = valid;
        }

        @Override
        public ValidationResult validateIssue(GitHubIssue issue) {
            if (valid) {
                return ValidationResult.valid();
            } else {
                return ValidationResult.invalid("INVALID", "Test failure");
            }
        }
    }

    /**
     * Fake implementation of IssueParser for testing
     */
    private static class FakeIssueParser extends IssueParser {
        @Override
        public ParsedIssue parseIssue(GitHubIssue issue) {
            ParsedIssue.IssueMetadata metadata = new ParsedIssue.IssueMetadata(
                    issue.getTitle(),
                    issue.getLabels(),
                    issue.getRepository());
            List<ParsedIssue.Section> sections = List.of(
                    new ParsedIssue.Section("Overview", "Test overview"),
                    new ParsedIssue.Section("Details", "Test details"));
            return new ParsedIssue(metadata, issue.getBody(), sections);
        }
    }

    /**
     * Fake implementation of RequirementsAnalyzer for testing
     */
    private static class FakeRequirementsAnalyzer implements RequirementsAnalyzer {
        @Override
        public AnalysisResult analyzeRequirements(ParsedIssue parsedIssue) {
            List<Requirement> functionalReqs = List.of(
                    new Requirement("System shall process data", Requirement.EarsPattern.UBIQUITOUS, true));
            List<Requirement> businessRules = List.of(
                    new Requirement("Data must be validated", Requirement.EarsPattern.UBIQUITOUS, true));
            return new AnalysisResult(
                    "Process test data",
                    List.of("User", "System"),
                    List.of("Stakeholder1"),
                    Collections.emptyList(),
                    functionalReqs,
                    Collections.emptyList(),
                    businessRules,
                    Collections.emptyList(),
                    List.of(new OpenQuestion("How to handle edge case?", "Critical for reliability", "High")));
        }
    }

    /**
     * Fake implementation of RequirementsTransformer for testing
     */
    private static class FakeRequirementsTransformer implements RequirementsTransformer {
        @Override
        public TransformedRequirements transformRequirements(AnalysisResult analysisResult) {
            List<GherkinScenario> functionalScenarios = List.of(
                    new GherkinScenario(
                            "User processes data",
                            List.of("user is authenticated"),
                            List.of("user submits data"),
                            List.of("system processes data")));
            List<GherkinScenario> acceptanceCriteria = new ArrayList<>();
            acceptanceCriteria.add(new GherkinScenario(
                    "Scenario 1",
                    List.of("given condition 1"),
                    List.of("when action 1"),
                    List.of("then result 1")));
            return new TransformedRequirements(
                    "# Test Requirements",
                    analysisResult.getIntent(),
                    analysisResult.getActors(),
                    List.of("Precondition 1"),
                    functionalScenarios,
                    List.of("Alt flow 1"),
                    List.of("Business rule 1"),
                    List.of("Data requirement 1"),
                    acceptanceCriteria,
                    List.of("Log metric 1"),
                    analysisResult.getAmbiguities());
        }
    }

    /**
     * Fake implementation of OutputGenerator for testing
     */
    private static class FakeOutputGenerator implements OutputGenerator {
        @Override
        public String generateOutput(TransformedRequirements requirements, String originalBody) {
            return "# Generated Output\n\n" + requirements.getIntent();
        }
    }

    /**
     * Fake implementation of LoopDetector for testing
     */
    private static class FakeLoopDetector implements LoopDetector {
        private final boolean shouldDetectLoop;
        private final int detectAtCheckpoint;
        private int checkpointCount = 0;

        FakeLoopDetector(boolean shouldDetectLoop) {
            this(shouldDetectLoop, 1);
        }

        FakeLoopDetector(boolean shouldDetectLoop, int detectAtCheckpoint) {
            this.shouldDetectLoop = shouldDetectLoop;
            this.detectAtCheckpoint = detectAtCheckpoint;
        }

        @Override
        public LoopDetectionResult checkForLoops(ProcessingContext context) {
            checkpointCount++;

            if (shouldDetectLoop && checkpointCount == detectAtCheckpoint) {
                return LoopDetectionResult.loopDetected(
                        "STOP: Loop detected at checkpoint " + checkpointCount,
                        "Loop condition detected in processing context");
            }

            return LoopDetectionResult.noLoop();
        }
    }

    /**
     * Always loop detector for testing - detects loop on every call
     */
    private static class AlwaysLoopDetector implements LoopDetector {
        @Override
        public LoopDetectionResult checkForLoops(ProcessingContext context) {
            return LoopDetectionResult.loopDetected(
                    "STOP: Loop detected",
                    "Loop condition detected in processing context");
        }
    }
}

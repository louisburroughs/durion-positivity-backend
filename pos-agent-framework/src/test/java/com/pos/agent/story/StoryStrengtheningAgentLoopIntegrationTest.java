package com.pos.agent.story;

import com.pos.agent.story.analysis.RequirementsAnalyzer;
import com.pos.agent.story.config.StoryConfiguration;
import com.pos.agent.story.loop.LoopDetectionResult;
import com.pos.agent.story.loop.LoopDetector;
import com.pos.agent.story.loop.ProcessingContext;
import com.pos.agent.story.models.*;
import com.pos.agent.story.models.Requirement.EarsPattern;
import com.pos.agent.story.output.OutputGenerator;
import com.pos.agent.story.parsing.IssueParser;
import com.pos.agent.story.transformation.RequirementsTransformer;
import com.pos.agent.story.validation.IssueValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for StoryStrengtheningAgent with LoopDetector.
 * Tests loop detection at various checkpoints in the processing pipeline.
 * 
 * Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7
 */
class StoryStrengtheningAgentLoopIntegrationTest {

    private GitHubIssue testIssue;
    private StoryConfiguration config;

    @BeforeEach
    void setUp() {
        testIssue = new GitHubIssue(
            "Test Issue",
            "Test body content",
            List.of("story"),
            "test-repo",
            123
        );
        config = StoryConfiguration.builder().build();
    }

    /**
     * Test scenario: No loop detected - processing completes successfully
     */
    @Test
    void testNoLoopDetected_SuccessfulProcessing() {
        // Given: All components return valid results and no loop is detected
        IssueValidator validator = new FakeIssueValidator(true);
        IssueParser parser = new FakeIssueParser();
        RequirementsAnalyzer analyzer = new FakeRequirementsAnalyzer();
        RequirementsTransformer transformer = new FakeRequirementsTransformer();
        OutputGenerator outputGenerator = new FakeOutputGenerator();
        LoopDetector loopDetector = new FakeLoopDetector(false); // No loop

        StoryStrengtheningAgent agent = new StoryStrengtheningAgent(
            validator, parser, analyzer, transformer, outputGenerator, loopDetector, config
        );

        // When: Processing the issue
        StoryStrengtheningAgent.ProcessingResult result = agent.processIssue(testIssue);

        // Then: Processing succeeds
        assertTrue(result.isSuccess(), "Processing should succeed when no loop detected");
        assertNotNull(result.getOutput(), "Output should be present");
        assertNull(result.getStopPhrase(), "Stop phrase should be null for success");
        assertNull(result.getReason(), "Reason should be null for success");
    }

    /**
     * Test scenario: Loop detected after parsing - early stop
     */
    @Test
    void testLoopDetectedAfterParsing_EarlyStop() {
        // Given: Loop detector reports loop at first checkpoint
        IssueValidator validator = new FakeIssueValidator(true);
        IssueParser parser = new FakeIssueParser();
        RequirementsAnalyzer analyzer = new FakeRequirementsAnalyzer();
        RequirementsTransformer transformer = new FakeRequirementsTransformer();
        OutputGenerator outputGenerator = new FakeOutputGenerator();
        LoopDetector loopDetector = new FakeLoopDetector(true, 1); // Detect loop at checkpoint 1

        StoryStrengtheningAgent agent = new StoryStrengtheningAgent(
            validator, parser, analyzer, transformer, outputGenerator, loopDetector, config
        );

        // When: Processing the issue
        StoryStrengtheningAgent.ProcessingResult result = agent.processIssue(testIssue);

        // Then: Processing stops after parsing
        assertFalse(result.isSuccess(), "Processing should stop when loop detected");
        assertNull(result.getOutput(), "Output should be null for stopped result");
        assertNotNull(result.getStopPhrase(), "Stop phrase should be present");
        assertTrue(result.getStopPhrase().contains("STOP"), "Stop phrase should indicate stop");
        assertNotNull(result.getReason(), "Reason should be present");
    }

    /**
     * Test scenario: Loop detected after analysis - mid stop
     */
    @Test
    void testLoopDetectedAfterAnalysis_MidStop() {
        // Given: Loop detector reports loop at second checkpoint
        IssueValidator validator = new FakeIssueValidator(true);
        IssueParser parser = new FakeIssueParser();
        RequirementsAnalyzer analyzer = new FakeRequirementsAnalyzer();
        RequirementsTransformer transformer = new FakeRequirementsTransformer();
        OutputGenerator outputGenerator = new FakeOutputGenerator();
        LoopDetector loopDetector = new FakeLoopDetector(true, 2); // Detect loop at checkpoint 2

        StoryStrengtheningAgent agent = new StoryStrengtheningAgent(
            validator, parser, analyzer, transformer, outputGenerator, loopDetector, config
        );

        // When: Processing the issue
        StoryStrengtheningAgent.ProcessingResult result = agent.processIssue(testIssue);

        // Then: Processing stops after analysis
        assertFalse(result.isSuccess(), "Processing should stop when loop detected");
        assertNull(result.getOutput(), "Output should be null for stopped result");
        assertNotNull(result.getStopPhrase(), "Stop phrase should be present");
        assertTrue(result.getStopPhrase().contains("STOP"), "Stop phrase should indicate stop");
        assertTrue(result.getReason().contains("Loop condition detected"), "Reason should mention loop");
    }

    /**
     * Test scenario: Loop detected after transformation - late stop
     */
    @Test
    void testLoopDetectedAfterTransformation_LateStop() {
        // Given: Loop detector reports loop at third checkpoint
        IssueValidator validator = new FakeIssueValidator(true);
        IssueParser parser = new FakeIssueParser();
        RequirementsAnalyzer analyzer = new FakeRequirementsAnalyzer();
        RequirementsTransformer transformer = new FakeRequirementsTransformer();
        OutputGenerator outputGenerator = new FakeOutputGenerator();
        LoopDetector loopDetector = new FakeLoopDetector(true, 3); // Detect loop at checkpoint 3

        StoryStrengtheningAgent agent = new StoryStrengtheningAgent(
            validator, parser, analyzer, transformer, outputGenerator, loopDetector, config
        );

        // When: Processing the issue
        StoryStrengtheningAgent.ProcessingResult result = agent.processIssue(testIssue);

        // Then: Processing stops after transformation
        assertFalse(result.isSuccess(), "Processing should stop when loop detected");
        assertNull(result.getOutput(), "Output should be null for stopped result");
        assertNotNull(result.getStopPhrase(), "Stop phrase should be present");
        assertTrue(result.getStopPhrase().contains("STOP"), "Stop phrase should indicate stop");
    }

    /**
     * Test scenario: Validation fails - should stop before loop checks
     */
    @Test
    void testValidationFails_StopsBeforeLoopChecks() {
        // Given: Validator returns invalid result
        IssueValidator validator = new FakeIssueValidator(false);
        IssueParser parser = new FakeIssueParser();
        RequirementsAnalyzer analyzer = new FakeRequirementsAnalyzer();
        RequirementsTransformer transformer = new FakeRequirementsTransformer();
        OutputGenerator outputGenerator = new FakeOutputGenerator();
        LoopDetector loopDetector = new FakeLoopDetector(false);

        StoryStrengtheningAgent agent = new StoryStrengtheningAgent(
            validator, parser, analyzer, transformer, outputGenerator, loopDetector, config
        );

        // When: Processing the issue
        StoryStrengtheningAgent.ProcessingResult result = agent.processIssue(testIssue);

        // Then: Processing stops at validation
        assertFalse(result.isSuccess(), "Processing should stop at validation");
        assertNotNull(result.getStopPhrase(), "Stop phrase should be present");
        assertTrue(result.getStopPhrase().contains("Validation failed"), "Stop phrase should mention validation");
    }

    // ======== Fake Implementations for Testing ========

    /**
     * Fake IssueValidator for testing
     */
    private static class FakeIssueValidator implements IssueValidator {
        private final boolean isValid;

        FakeIssueValidator(boolean isValid) {
            this.isValid = isValid;
        }

        @Override
        public ValidationResult validateIssue(GitHubIssue issue) {
            if (isValid) {
                return ValidationResult.valid();
            } else {
                return ValidationResult.invalid("STOP: Validation failed", "Issue does not meet criteria");
            }
        }
    }

    /**
     * Fake IssueParser for testing
     */
    private static class FakeIssueParser extends IssueParser {
        @Override
        public ParsedIssue parseIssue(GitHubIssue issue) {
            ParsedIssue.IssueMetadata metadata = new ParsedIssue.IssueMetadata(
                issue.getTitle(),
                issue.getLabels(),
                issue.getRepository()
            );
            List<ParsedIssue.Section> sections = List.of(
                new ParsedIssue.Section("Overview", "Test overview"),
                new ParsedIssue.Section("Details", "Test details")
            );
            return new ParsedIssue(metadata, issue.getBody(), sections);
        }
    }

    /**
     * Fake RequirementsAnalyzer for testing
     */
    private static class FakeRequirementsAnalyzer implements RequirementsAnalyzer {
        @Override
        public AnalysisResult analyzeRequirements(ParsedIssue parsedIssue) {
            List<Requirement> functionalReqs = List.of(
                new Requirement("System shall process data", EarsPattern.UBIQUITOUS, true)
            );
            List<Requirement> businessRules = List.of(
                new Requirement("Data must be validated", EarsPattern.UBIQUITOUS, true)
            );
            return new AnalysisResult(
                "Process test data",
                List.of("User", "System"),
                List.of("Stakeholder1"),
                Collections.emptyList(),
                functionalReqs,
                Collections.emptyList(),
                businessRules,
                Collections.emptyList(),
                List.of(new OpenQuestion("How to handle edge case?", "Critical for reliability", "High"))
            );
        }
    }

    /**
     * Fake RequirementsTransformer for testing
     */
    private static class FakeRequirementsTransformer implements RequirementsTransformer {
        @Override
        public TransformedRequirements transformRequirements(AnalysisResult analysisResult) {
            List<GherkinScenario> functionalScenarios = List.of(
                new GherkinScenario(
                    "User processes data",
                    List.of("user is authenticated"),
                    List.of("user submits data"),
                    List.of("system processes data")
                )
            );
            List<GherkinScenario> acceptanceCriteria = new ArrayList<>();
            // Add just a few scenarios (under threshold)
            for (int i = 0; i < 5; i++) {
                acceptanceCriteria.add(new GherkinScenario(
                    "Scenario " + i,
                    List.of("given condition " + i),
                    List.of("when action " + i),
                    List.of("then result " + i)
                ));
            }
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
                analysisResult.getAmbiguities()
            );
        }
    }

    /**
     * Fake OutputGenerator for testing
     */
    private static class FakeOutputGenerator implements OutputGenerator {
        @Override
        public String generateOutput(TransformedRequirements requirements, String originalBody) {
            return "# Generated Output\n\n" + requirements.getIntent();
        }
    }

    /**
     * Fake LoopDetector for testing - allows control of when loop is detected
     */
    private static class FakeLoopDetector implements LoopDetector {
        private final boolean shouldDetectLoop;
        private final int detectAtCheckpoint; // Which checkpoint to detect loop at (1, 2, 3)
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
                    "Loop condition detected in processing context"
                );
            }
            
            return LoopDetectionResult.noLoop();
        }
    }
}

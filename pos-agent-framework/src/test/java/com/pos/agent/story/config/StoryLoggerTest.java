package com.pos.agent.story.config;

import com.pos.agent.story.models.*;
import com.pos.agent.story.loop.LoopDetectionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for StoryLogger.
 * 
 * Requirements: Task 15.2 - Test logging functionality
 */
class StoryLoggerTest {
    
    private StoryLogger logger;
    private ByteArrayOutputStream outputStream;
    private PrintStream originalOut;
    
    @BeforeEach
    void setUp() {
        // Capture System.out for testing
        outputStream = new ByteArrayOutputStream();
        originalOut = System.out;
        System.setOut(new PrintStream(outputStream));
        
        logger = new StoryLogger("TEST-SESSION-001");
    }
    
    @AfterEach
    void tearDown() {
        // Restore System.out
        System.setOut(originalOut);
    }
    
    @Test
    void testSessionStartLogging() {
        String output = outputStream.toString();
        
        assertTrue(output.contains("STORY STRENGTHENING SESSION STARTED"));
        assertTrue(output.contains("Session ID: TEST-SESSION-001"));
        assertTrue(output.contains("Story Strengthening Agent"));
    }
    
    @Test
    void testValidationStartLogging() {
        GitHubIssue issue = new GitHubIssue(
            "[BACKEND] [STORY] Test Story",
            "Test body",
            Arrays.asList("story", "backend"),
            "durion-positivity-backend",
            123
        );
        
        logger.logValidationStart(issue);
        String output = outputStream.toString();
        
        assertTrue(output.contains("VALIDATION PHASE STARTED"));
        assertTrue(output.contains("Issue Title: [BACKEND] [STORY] Test Story"));
        assertTrue(output.contains("Repository: durion-positivity-backend"));
        assertTrue(output.contains("Labels: story, backend"));
    }
    
    @Test
    void testValidationSuccessLogging() {
        logger.logValidationSuccess(150L);
        String output = outputStream.toString();
        
        assertTrue(output.contains("VALIDATION PHASE COMPLETED"));
        assertTrue(output.contains("Status: SUCCESS"));
        assertTrue(output.contains("Duration: 150ms"));
    }
    
    @Test
    void testValidationFailureLogging() {
        ValidationResult result = ValidationResult.invalid(
            "STOP: Repository not in scope",
            "Repository must be durion-positivity-backend"
        );
        
        logger.logValidationFailure(result, 100L);
        String output = outputStream.toString();
        
        assertTrue(output.contains("VALIDATION PHASE FAILED"));
        assertTrue(output.contains("Status: FAILURE"));
        assertTrue(output.contains("Duration: 100ms"));
        assertTrue(output.contains("Stop Phrase: STOP: Repository not in scope"));
        assertTrue(output.contains("Reason: Repository must be durion-positivity-backend"));
    }
    
    @Test
    void testParsingStartLogging() {
        logger.logParsingStart();
        String output = outputStream.toString();
        
        assertTrue(output.contains("PARSING PHASE STARTED"));
    }
    
    @Test
    void testParsingCompleteLogging() {
        ParsedIssue.IssueMetadata metadata = new ParsedIssue.IssueMetadata(
            "Test Title",
            Arrays.asList("story"),
            "durion-positivity-backend"
        );
        
        ParsedIssue parsedIssue = new ParsedIssue(
            metadata,
            "Test Body",
            Arrays.asList(
                new ParsedIssue.Section("Introduction", "Test intro"),
                new ParsedIssue.Section("Requirements", "Test requirements")
            )
        );
        
        logger.logParsingComplete(parsedIssue, 200L);
        String output = outputStream.toString();
        
        assertTrue(output.contains("PARSING PHASE COMPLETED"));
        assertTrue(output.contains("Status: SUCCESS"));
        assertTrue(output.contains("Duration: 200ms"));
        assertTrue(output.contains("Sections Parsed: 2"));
    }
    
    @Test
    void testAnalysisStartLogging() {
        logger.logAnalysisStart();
        String output = outputStream.toString();
        
        assertTrue(output.contains("ANALYSIS PHASE STARTED"));
    }
    
    @Test
    void testAnalysisCompleteLogging() {
        AnalysisResult analysisResult = new AnalysisResult(
            "Test intent",
            Arrays.asList("User", "Admin"),
            Arrays.asList("Stakeholder1"),
            Arrays.asList(new Requirement("REQ-0", Requirement.EarsPattern.STATE_DRIVEN, true)),
            Arrays.asList(new Requirement("REQ-1", Requirement.EarsPattern.UBIQUITOUS, true)),
            Arrays.asList(new Requirement("REQ-2", Requirement.EarsPattern.EVENT_DRIVEN, true)),
            Arrays.asList(new Requirement("REQ-3", Requirement.EarsPattern.UBIQUITOUS, true)),
            Arrays.asList(new AnalysisResult.DataRequirement("field1", "Test data", true)),
            Arrays.asList(
                new OpenQuestion("What is the timeout?", "Affects performance", "high"),
                new OpenQuestion("What is the retry policy?", "Affects reliability", "medium")
            )
        );
        
        logger.logAnalysisComplete(analysisResult, 300L);
        String output = outputStream.toString();
        
        assertTrue(output.contains("ANALYSIS PHASE COMPLETED"));
        assertTrue(output.contains("Status: SUCCESS"));
        assertTrue(output.contains("Duration: 300ms"));
        assertTrue(output.contains("Actors Identified: 2"));
        assertTrue(output.contains("Functional Requirements: 1"));
        assertTrue(output.contains("Business Rules: 1"));
        assertTrue(output.contains("Data Requirements: 1"));
        assertTrue(output.contains("Ambiguities Detected: 2"));
        assertTrue(output.contains("What is the timeout?"));
    }
    
    @Test
    void testAmbiguityDetectionLogging() {
        List<OpenQuestion> ambiguities = Arrays.asList(
            new OpenQuestion("What is the timeout?", "Affects performance", "high"),
            new OpenQuestion("What is the retry policy?", "Affects reliability", "medium")
        );
        
        logger.logAmbiguityDetection(ambiguities);
        String output = outputStream.toString();
        
        assertTrue(output.contains("AMBIGUITIES DETECTED: 2"));
        assertTrue(output.contains("Question: What is the timeout?"));
        assertTrue(output.contains("Impact: Affects performance"));
        assertTrue(output.contains("Question: What is the retry policy?"));
        assertTrue(output.contains("Impact: Affects reliability"));
    }
    
    @Test
    void testAmbiguityDetectionLoggingEmpty() {
        logger.logAmbiguityDetection(Collections.emptyList());
        String output = outputStream.toString();
        
        assertTrue(output.contains("NO AMBIGUITIES DETECTED"));
    }
    
    @Test
    void testTransformationStartLogging() {
        logger.logTransformationStart();
        String output = outputStream.toString();
        
        assertTrue(output.contains("TRANSFORMATION PHASE STARTED"));
    }
    
    @Test
    void testTransformationCompleteLogging() {
        TransformedRequirements transformedRequirements = new TransformedRequirements(
            "Test Header",
            "Test intent",
            Arrays.asList("User", "Admin"),
            Arrays.asList("WHILE system is running"),
            Arrays.asList(new GherkinScenario("Scenario 1", 
                Arrays.asList("context"), 
                Arrays.asList("action"), 
                Arrays.asList("outcome"))),
            Arrays.asList("WHEN error occurs THEN system SHALL log"),
            Arrays.asList("Business rule 1"),
            Arrays.asList("Data requirement 1"),
            Arrays.asList(new GherkinScenario("AC 1", 
                Arrays.asList("setup"), 
                Arrays.asList("trigger"), 
                Arrays.asList("result"))),
            Arrays.asList("Observability requirement 1"),
            Arrays.asList(new OpenQuestion("What is the timeout?", "Affects performance", "high"))
        );
        
        logger.logTransformationComplete(transformedRequirements, 400L);
        String output = outputStream.toString();
        
        assertTrue(output.contains("TRANSFORMATION PHASE COMPLETED"));
        assertTrue(output.contains("Status: SUCCESS"));
        assertTrue(output.contains("Duration: 400ms"));
        assertTrue(output.contains("Functional Requirements: 1"));
        assertTrue(output.contains("Gherkin Scenarios: 1"));
        assertTrue(output.contains("Open Questions: 1"));
    }
    
    @Test
    void testOutputGenerationStartLogging() {
        logger.logOutputGenerationStart();
        String output = outputStream.toString();
        
        assertTrue(output.contains("OUTPUT GENERATION PHASE STARTED"));
    }
    
    @Test
    void testOutputGenerationCompleteLogging() {
        logger.logOutputGenerationComplete(5000, 100L);
        String output = outputStream.toString();
        
        assertTrue(output.contains("OUTPUT GENERATION PHASE COMPLETED"));
        assertTrue(output.contains("Status: SUCCESS"));
        assertTrue(output.contains("Duration: 100ms"));
        assertTrue(output.contains("Output Length: 5000 characters"));
    }
    
    @Test
    void testLoopDetectionCheckNoLoop() {
        LoopDetectionResult result = LoopDetectionResult.noLoop();
        
        logger.logLoopDetectionCheck("After Parsing", result);
        String output = outputStream.toString();
        
        assertTrue(output.contains("LOOP DETECTION CHECK: After Parsing"));
        assertTrue(output.contains("Loop Detected: false"));
        assertTrue(output.contains("No loop detected, continuing processing"));
    }
    
    @Test
    void testLoopDetectionCheckWithLoop() {
        LoopDetectionResult result = LoopDetectionResult.loopDetected(
            "STOP: Excessive ambiguity",
            "Open questions exceed threshold"
        );
        
        logger.logLoopDetectionCheck("After Analysis", result);
        String output = outputStream.toString();
        
        assertTrue(output.contains("LOOP DETECTION CHECK: After Analysis"));
        assertTrue(output.contains("Loop Detected: true"));
        assertTrue(output.contains("Stop Phrase: STOP: Excessive ambiguity"));
        assertTrue(output.contains("Details: Open questions exceed threshold"));
        assertTrue(output.contains("PROCESSING HALTED DUE TO LOOP CONDITION"));
    }
    
    @Test
    void testOpenQuestionsCountBelowThreshold() {
        logger.logOpenQuestionsCount(5, 10);
        String output = outputStream.toString();
        
        assertTrue(output.contains("OPEN QUESTIONS MONITORING"));
        assertTrue(output.contains("Current Count: 5"));
        assertTrue(output.contains("Threshold: 10"));
        assertTrue(output.contains("Open questions at 50.0% of threshold"));
    }
    
    @Test
    void testOpenQuestionsCountAtThreshold() {
        logger.logOpenQuestionsCount(10, 10);
        String output = outputStream.toString();
        
        assertTrue(output.contains("OPEN QUESTIONS MONITORING"));
        assertTrue(output.contains("Current Count: 10"));
        assertTrue(output.contains("Threshold: 10"));
        assertTrue(output.contains("WARNING: Open questions at or above threshold"));
    }
    
    @Test
    void testProcessingTimeSummary() {
        logger.logProcessingTimeSummary(100L, 200L, 300L, 400L, 500L);
        String output = outputStream.toString();
        
        assertTrue(output.contains("PROCESSING TIME SUMMARY"));
        assertTrue(output.contains("Validation: 100ms"));
        assertTrue(output.contains("Parsing: 200ms"));
        assertTrue(output.contains("Analysis: 300ms"));
        assertTrue(output.contains("Transformation: 400ms"));
        assertTrue(output.contains("Output Generation: 500ms"));
        assertTrue(output.contains("Total: 1500ms"));
    }
    
    @Test
    void testErrorLogging() {
        Exception error = new RuntimeException("Test error", new IllegalArgumentException("Root cause"));
        
        logger.logError("Validation", error, "Testing error logging");
        String output = outputStream.toString();
        
        assertTrue(output.contains("ERROR IN VALIDATION"));
        assertTrue(output.contains("Error Type: RuntimeException"));
        assertTrue(output.contains("Error Message: Test error"));
        assertTrue(output.contains("Context: Testing error logging"));
        assertTrue(output.contains("Session: TEST-SESSION-001"));
        assertTrue(output.contains("Caused by: IllegalArgumentException: Root cause"));
    }
    
    @Test
    void testSessionEndSuccess() {
        logger.logSessionEnd(true, "Completed successfully");
        String output = outputStream.toString();
        
        assertTrue(output.contains("STORY STRENGTHENING SESSION COMPLETED"));
        assertTrue(output.contains("Session ID: TEST-SESSION-001"));
        assertTrue(output.contains("Final Status: Completed successfully"));
        assertTrue(output.contains("Success: true"));
    }
    
    @Test
    void testSessionEndFailure() {
        logger.logSessionEnd(false, "Failed due to validation error");
        String output = outputStream.toString();
        
        assertTrue(output.contains("STORY STRENGTHENING SESSION COMPLETED"));
        assertTrue(output.contains("Session ID: TEST-SESSION-001"));
        assertTrue(output.contains("Final Status: Failed due to validation error"));
        assertTrue(output.contains("Success: false"));
    }
    
    @Test
    void testGetSessionId() {
        assertEquals("TEST-SESSION-001", logger.getSessionId());
    }
    
    @Test
    void testGetSessionStartTime() {
        LocalDateTime startTime = logger.getSessionStartTime();
        assertNotNull(startTime);
        assertTrue(startTime.isBefore(LocalDateTime.now().plusSeconds(1)));
    }
}

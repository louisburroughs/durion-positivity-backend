package com.pos.agent.impl;

import com.pos.agent.context.AgentContext;
import com.pos.agent.context.DefaultContext;
import com.pos.agent.context.StoryContext;
import com.pos.agent.core.AgentProcessingState;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for StoryStrengtheningAgent aiming for >80% coverage.
 */
@ExtendWith(MockitoExtension.class)
class StoryStrengtheningAgentTest {

    @Mock
    private IssueValidator mockValidator;
    @Mock
    private IssueParser mockParser;
    @Mock
    private RequirementsAnalyzer mockAnalyzer;
    @Mock
    private RequirementsTransformer mockTransformer;
    @Mock
    private OutputGenerator mockOutputGenerator;
    @Mock
    private LoopDetector mockLoopDetector;
    @Mock
    private StoryConfiguration mockConfiguration;

    private StoryStrengtheningAgent agent;

    @BeforeEach
    void setUp() {
        agent = new StoryStrengtheningAgent(
                mockValidator, mockParser, mockAnalyzer, mockTransformer,
                mockOutputGenerator, mockLoopDetector, mockConfiguration);
    }

    @Test
    void testConstructor_NullValidator_ThrowsException() {
        assertThatThrownBy(() -> new StoryStrengtheningAgent(
                null, mockParser, mockAnalyzer, mockTransformer,
                mockOutputGenerator, mockLoopDetector, mockConfiguration)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testGetOrCreateContext_CreatesNewContext() {
        AgentContext context = agent.getOrCreateContext("session-1");
        assertThat(context).isInstanceOf(StoryContext.class);
    }

    @Test
    void testGetOrCreateContext_ReturnsSameContext() {
        AgentContext ctx1 = agent.getOrCreateContext("session-2");
        AgentContext ctx2 = agent.getOrCreateContext("session-2");
        assertThat(ctx1).isSameAs(ctx2);
    }

    @Test
    void testRemoveContext() {
        agent.getOrCreateContext("session-3");
        agent.removeContext("session-3");
        assertThat(agent.getOrCreateContext("session-3")).isNotNull();
    }

    @Test
    void testProcessingResult_Success() {
        var result = StoryStrengtheningAgent.ProcessingResult.success("output");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("output");
    }

    @Test
    void testProcessingResult_Stopped() {
        var result = StoryStrengtheningAgent.ProcessingResult.stopped("STOP", "reason");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStopPhrase()).isEqualTo("STOP");
    }

    @Test
    void testProcessRequest_Success() throws Exception {
        setupSuccessfulProcessing();

        AgentRequest request = createRequest("User story");
        AgentResponse response = agent.processRequest(request);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void testProcessRequest_ValidationFailure() {
        when(mockValidator.validateIssue(any())).thenReturn(
                ValidationResult.invalid("STOP", "Invalid"));

        AgentResponse response = agent.processRequest(createRequest("test"));

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getStatus()).isEqualTo("FAILURE");
    }

    @Test
    void testProcessRequest_ExceptionHandling() {
        when(mockValidator.validateIssue(any())).thenThrow(new RuntimeException("Error"));

        AgentResponse response = agent.processRequest(createRequest("test"));

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getOutput()).contains("Story strengthening failed");
    }

    @Test
    void testProcessIssue_SuccessPath() throws Exception {
        setupSuccessfulProcessing();

        var result = agent.processIssue(createIssue());

        assertThat(result.isSuccess()).isTrue();
        verify(mockValidator).validateIssue(any());
        verify(mockParser).parseIssue(any());
        verify(mockAnalyzer).analyzeRequirements(any());
        verify(mockTransformer).transformRequirements(any());
        verify(mockOutputGenerator).generateOutput(any(), any());
    }

    @Test
    void testProcessIssue_ValidationFails() {
        when(mockValidator.validateIssue(any())).thenReturn(
                ValidationResult.invalid("STOP", "Invalid"));

        var result = agent.processIssue(createIssue());

        assertThat(result.isSuccess()).isFalse();
        verify(mockValidator).validateIssue(any());
        verifyNoInteractions(mockParser);
    }

    @Test
    void testProcessIssue_LoopDetected() throws Exception {
        when(mockValidator.validateIssue(any())).thenReturn(ValidationResult.valid());
        when(mockParser.parseIssue(any())).thenReturn(createParsedIssue());
        when(mockLoopDetector.checkForLoops(any())).thenReturn(
                LoopDetectionResult.loopDetected("STOP", "Loop"));

        var result = agent.processIssue(createIssue());

        assertThat(result.isSuccess()).isFalse();
        verify(mockLoopDetector).checkForLoops(any());
    }

    @Test
    void testProcessIssue_ParserException() throws Exception {
        when(mockValidator.validateIssue(any())).thenReturn(ValidationResult.valid());
        when(mockParser.parseIssue(any())).thenThrow(
                new IssueParser.IssueParserException("Parse error"));

        var result = agent.processIssue(createIssue());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStopPhrase()).contains("parsing failed");
    }

    @Test
    void testProcessIssue_NullIssue() {
        assertThatThrownBy(() -> agent.processIssue(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testCapabilities() {
        assertThat(agent.getCapabilities()).contains("story-analysis");
    }

    // Helper methods
    private void setupSuccessfulProcessing() throws Exception {
        when(mockValidator.validateIssue(any())).thenReturn(ValidationResult.valid());
        when(mockParser.parseIssue(any())).thenReturn(createParsedIssue());
        when(mockAnalyzer.analyzeRequirements(any())).thenReturn(createAnalysisResult());
        when(mockTransformer.transformRequirements(any())).thenReturn(createTransformedRequirements());
        when(mockLoopDetector.checkForLoops(any())).thenReturn(LoopDetectionResult.noLoop());
        when(mockOutputGenerator.generateOutput(any(), any())).thenReturn("Output");
    }

    private GitHubIssue createIssue() {
        return new GitHubIssue("Title", "Body", List.of("story"), "repo", 1);
    }

    private ParsedIssue createParsedIssue() {
        return new ParsedIssue(
                new ParsedIssue.IssueMetadata("Title", List.of("story"), "repo"),
                "Body",
                List.of(new ParsedIssue.Section("Section", "Content")));
    }

    private AnalysisResult createAnalysisResult() {
        return new AnalysisResult(
                "Intent", List.of("Actor"), List.of(), List.of(),
                List.of(new Requirement("Req", Requirement.EarsPattern.UBIQUITOUS, true)),
                List.of(), List.of(), List.of(), List.of());
    }

    private TransformedRequirements createTransformedRequirements() {
        return new TransformedRequirements(
                "Header", "Intent", List.of("Actor"), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private AgentRequest createRequest(String description) {
        return AgentRequest.builder()
                .agentContext(DefaultContext.builder().description(description).build())
                .securityContext(com.pos.agent.core.SecurityContext.builder()
                        .userId("test-user")
                        .roles(List.of())
                        .permissions(List.of())
                        .serviceId("test-service")
                        .serviceType("test")
                        .build())
                .build();
    }
    // ===== Edge Case Tests for Better Branch Coverage =====

    @Test
    void testProcessIssue_LoopDetectedAfterAnalysis()
            throws com.pos.agent.story.parsing.IssueParser.IssueParserException {
        when(mockValidator.validateIssue(any())).thenReturn(ValidationResult.valid());
        when(mockParser.parseIssue(any())).thenReturn(createParsedIssue());
        when(mockLoopDetector.checkForLoops(any()))
                .thenReturn(LoopDetectionResult.noLoop()) // After parsing
                .thenReturn(LoopDetectionResult.loopDetected("LOOP_DETECTED", "Analysis loop"));
        when(mockAnalyzer.analyzeRequirements(any())).thenReturn(createAnalysisResult());

        StoryStrengtheningAgent.ProcessingResult result = agent.processIssue(createIssue());

        assertThat(result.getStopPhrase()).contains("LOOP_DETECTED");
    }

    @Test
    void testProcessIssue_LoopDetectedAfterTransformation()
            throws com.pos.agent.story.parsing.IssueParser.IssueParserException {
        when(mockValidator.validateIssue(any())).thenReturn(ValidationResult.valid());
        when(mockParser.parseIssue(any())).thenReturn(createParsedIssue());
        when(mockAnalyzer.analyzeRequirements(any())).thenReturn(createAnalysisResult());
        when(mockTransformer.transformRequirements(any())).thenReturn(createTransformedRequirements());
        when(mockLoopDetector.checkForLoops(any()))
                .thenReturn(LoopDetectionResult.noLoop()) // After parsing
                .thenReturn(LoopDetectionResult.noLoop()) // After analysis
                .thenReturn(LoopDetectionResult.loopDetected("TRANSFORMATION_LOOP", "Transform loop"));

        StoryStrengtheningAgent.ProcessingResult result = agent.processIssue(createIssue());

        assertThat(result.getStopPhrase()).contains("TRANSFORMATION_LOOP");
    }

    @Test
    void testProcessIssue_OutputGenerationSuccess()
            throws com.pos.agent.story.parsing.IssueParser.IssueParserException {
        String expectedOutput = "# Strengthened Story\nAs a user...";
        when(mockValidator.validateIssue(any())).thenReturn(ValidationResult.valid());
        when(mockParser.parseIssue(any())).thenReturn(createParsedIssue());
        when(mockAnalyzer.analyzeRequirements(any())).thenReturn(createAnalysisResult());
        when(mockTransformer.transformRequirements(any())).thenReturn(createTransformedRequirements());
        when(mockLoopDetector.checkForLoops(any())).thenReturn(LoopDetectionResult.noLoop());
        when(mockOutputGenerator.generateOutput(any(), any())).thenReturn(expectedOutput);

        StoryStrengtheningAgent.ProcessingResult result = agent.processIssue(createIssue());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo(expectedOutput);
    }

    @Test
    void testProcessRequest_WithContextProperties() throws Exception {
        setupSuccessfulProcessing();
        DefaultContext context = new DefaultContext.Builder()
                .description("Issue with context")
                .property("body", "Custom body content")
                .property("repo", "custom-repo")
                .build();

        AgentRequest request = AgentRequest.builder()
                .agentContext(context)
                .securityContext(com.pos.agent.core.SecurityContext.builder()
                        .userId("test-user")
                        .roles(List.of())
                        .permissions(List.of())
                        .build())
                .build();

        AgentResponse response = agent.processRequest(request);

        assertThat(response.isSuccess()).isTrue();
    }

    @Test
    void testProcessRequest_NullContextProperties() throws Exception {
        setupSuccessfulProcessing();
        // Create context with null properties map
        DefaultContext context = new DefaultContext.Builder()
                .description("Issue with null props")
                .build();

        AgentRequest request = AgentRequest.builder()
                .agentContext(context)
                .securityContext(com.pos.agent.core.SecurityContext.builder()
                        .userId("test-user")
                        .build())
                .build();

        AgentResponse response = agent.processRequest(request);

        assertThat(response.isSuccess()).isTrue();
    }

    @Test
    void testProcessIssue_ParserExceptionWithCustomMessage()
            throws com.pos.agent.story.parsing.IssueParser.IssueParserException {
        when(mockValidator.validateIssue(any())).thenReturn(ValidationResult.valid());
        when(mockParser.parseIssue(any()))
                .thenThrow(new com.pos.agent.story.parsing.IssueParser.IssueParserException("Custom parse error"));

        StoryStrengtheningAgent.ProcessingResult result = agent.processIssue(createIssue());

        assertThat(result.getReason()).isEqualTo("Custom parse error");
    }

    @Test
    void testGetCapabilities() {
        var capabilities = agent.getCapabilities();

        assertThat(capabilities).isNotNull();
        assertThat(capabilities).isNotEmpty();
        assertThat(capabilities).contains("story-analysis", "requirement-analysis", "loop-detection");
    }

    @Test
    void testProcessRequest_ExceptionDuringProcessing() {
        when(mockValidator.validateIssue(any())).thenThrow(new RuntimeException("Unexpected error"));

        AgentRequest request = createRequest("Test issue");
        AgentResponse response = agent.processRequest(request);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getStatus()).isEqualTo("FAILURE");
        assertThat(response.getErrorMessage()).contains("Story strengthening failed");
    }

    @Test
    void testContextManagement_MultipleContexts() {
        var context1 = agent.getOrCreateContext("session-1");
        var context2 = agent.getOrCreateContext("session-2");
        var context1Again = agent.getOrCreateContext("session-1");

        assertThat(context1).isSameAs(context1Again);
        assertThat(context1).isNotSameAs(context2);

        agent.removeContext("session-1");
        var context1New = agent.getOrCreateContext("session-1");

        assertThat(context1New).isNotSameAs(context1);
    }
}

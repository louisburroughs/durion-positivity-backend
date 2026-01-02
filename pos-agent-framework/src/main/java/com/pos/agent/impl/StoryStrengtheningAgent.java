package com.pos.agent.impl;

import com.pos.agent.context.AgentContext;
import com.pos.agent.core.AbstractAgent;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.core.AgentProcessingState;
import com.pos.agent.framework.model.AgentType;
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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Main orchestrator for the Story Strengthening Agent pipeline.
 * Coordinates validation, parsing, analysis, transformation, and output
 * generation.
 * Extends AbstractAgent for common validation and failure handling.
 * 
 * This class follows the reference pattern from
 * workspace-agents/audit/MissingIssuesAuditSystem.
 * 
 * Requirements: All requirements
 */
public class StoryStrengtheningAgent extends AbstractAgent {
    private final IssueValidator validator;
    private final IssueParser parser;
    private final RequirementsAnalyzer analyzer;
    private final RequirementsTransformer transformer;
    private final OutputGenerator outputGenerator;
    private final LoopDetector loopDetector;
    private final StoryConfiguration configuration;

    /**
     * Creates a new Story Strengthening Agent with all required components.
     * 
     * @param validator       Issue validator
     * @param parser          Issue parser
     * @param analyzer        Requirements analyzer
     * @param transformer     Requirements transformer
     * @param outputGenerator Output generator
     * @param loopDetector    Loop detector
     * @param configuration   Agent configuration
     */
    public StoryStrengtheningAgent(
            IssueValidator validator,
            IssueParser parser,
            RequirementsAnalyzer analyzer,
            RequirementsTransformer transformer,
            OutputGenerator outputGenerator,
            LoopDetector loopDetector,
            StoryConfiguration configuration) {
        super(AgentType.BUSINESS_DOMAIN, List.of(
                "story-analysis",
                "requirement-analysis",
                "requirement-transformation",
                "quality-improvement",
                "loop-detection"));
        this.validator = Objects.requireNonNull(validator, "Validator cannot be null");
        this.parser = Objects.requireNonNull(parser, "Parser cannot be null");
        this.analyzer = Objects.requireNonNull(analyzer, "Analyzer cannot be null");
        this.transformer = Objects.requireNonNull(transformer, "Transformer cannot be null");
        this.outputGenerator = Objects.requireNonNull(outputGenerator, "Output generator cannot be null");
        this.loopDetector = Objects.requireNonNull(loopDetector, "Loop detector cannot be null");
        this.configuration = Objects.requireNonNull(configuration, "Configuration cannot be null");
    }

    /**
     * Implementation of abstract method from AbstractAgent.
     * Converts AgentRequest to domain-specific GitHubIssue and processes it.
     * 
     * @param request The agent request
     * @return The agent response
     */
    @Override
    protected AgentResponse doProcessRequest(AgentRequest request) {
        try {
            // Extract issue data from request context
            GitHubIssue issue = extractIssueFromRequest(request);
            ProcessingResult processingResult = processIssue(issue);

            if (processingResult.isSuccess()) {
                return AgentResponse.builder()
                        .status(AgentProcessingState.SUCCESS)
                        .output(processingResult.getOutput())
                        .success(true)
                        .confidence(0.9)
                        .recommendations(Collections.singletonList("Review and refine the strengthened requirements"))
                        .build();
            } else {
                return AgentResponse.builder()
                        .status(AgentProcessingState.FAILURE)
                        .output(processingResult.getStopPhrase() + ": " + processingResult.getReason())
                        .success(false)
                        .confidence(0.0)
                        .errorMessage(processingResult.getReason())
                        .recommendations(Collections.emptyList())
                        .build();
            }
        } catch (Exception e) {
            return createFailureResponse("Story strengthening failed: " + e.getMessage());
        }
    }

    /**
     * Extracts a GitHubIssue from an AgentRequest.
     * This is a simple extraction - in a real implementation, this would parse
     * the request context to extract the issue details.
     * 
     * @param request The agent request
     * @return A GitHubIssue extracted from the request
     */
    private GitHubIssue extractIssueFromRequest(AgentRequest request) {
        String body = request.getAgentContext().getDescription();
        String repo = "unknown";

        // Extract from AgentContext properties if available
        AgentContext agentContext = request.getAgentContext();
        if (agentContext != null) {
            Map<String, Object> contextMap = agentContext.getProperties();
            if (contextMap != null) {
                Object bodyObj = contextMap.get("body");
                Object repoObj = contextMap.get("repo");
                if (bodyObj != null) {
                    body = bodyObj.toString();
                }
                if (repoObj != null) {
                    repo = repoObj.toString();
                }
            }
        }

        return new GitHubIssue(
                request.getAgentContext().getDescription(),
                body,
                Collections.singletonList("story"),
                repo,
                0);
    }

    /**
     * Processes a GitHub issue through the complete pipeline.
     * Returns the strengthened requirements document or a stop phrase if processing
     * cannot continue.
     * 
     * @param issue The GitHub issue to process
     * @return ProcessingResult with output or stop phrase
     */
    public ProcessingResult processIssue(GitHubIssue issue) {
        Objects.requireNonNull(issue, "Issue cannot be null");

        try {
            // Step 1: Validation
            ValidationResult validationResult = validator.validateIssue(issue);
            if (!validationResult.isValid()) {
                return ProcessingResult.stopped(
                        validationResult.getStopPhrase().orElse("STOP: Validation failed"),
                        validationResult.getReason().orElse("Unknown reason"));
            }

            // Step 2: Parsing
            ParsedIssue parsedIssue = parser.parseIssue(issue);

            // Loop Detection Checkpoint 1: After parsing
            ProcessingContext contextAfterParsing = buildProcessingContext(parsedIssue, null, null);
            LoopDetectionResult loopCheckAfterParsing = loopDetector.checkForLoops(contextAfterParsing);
            if (loopCheckAfterParsing.isLoopDetected()) {
                return ProcessingResult.stopped(
                        loopCheckAfterParsing.getStopPhrase().orElse("STOP: Loop detected after parsing"),
                        loopCheckAfterParsing.getDetails().orElse("Loop condition detected"));
            }

            // Step 3: Analysis
            AnalysisResult analysisResult = analyzer.analyzeRequirements(parsedIssue);

            // Loop Detection Checkpoint 2: After analysis
            ProcessingContext contextAfterAnalysis = buildProcessingContext(parsedIssue, analysisResult, null);
            LoopDetectionResult loopCheckAfterAnalysis = loopDetector.checkForLoops(contextAfterAnalysis);
            if (loopCheckAfterAnalysis.isLoopDetected()) {
                return ProcessingResult.stopped(
                        loopCheckAfterAnalysis.getStopPhrase().orElse("STOP: Loop detected after analysis"),
                        loopCheckAfterAnalysis.getDetails().orElse("Loop condition detected"));
            }

            // Step 4: Transformation
            TransformedRequirements transformedRequirements = transformer.transformRequirements(analysisResult);

            // Loop Detection Checkpoint 3: After transformation
            ProcessingContext contextAfterTransformation = buildProcessingContext(parsedIssue, analysisResult,
                    transformedRequirements);
            LoopDetectionResult loopCheckAfterTransformation = loopDetector.checkForLoops(contextAfterTransformation);
            if (loopCheckAfterTransformation.isLoopDetected()) {
                return ProcessingResult.stopped(
                        loopCheckAfterTransformation.getStopPhrase().orElse("STOP: Loop detected after transformation"),
                        loopCheckAfterTransformation.getDetails().orElse("Loop condition detected"));
            }

            // Step 5: Output Generation
            String output = outputGenerator.generateOutput(transformedRequirements, issue.getBody());

            return ProcessingResult.success(output);

        } catch (com.pos.agent.story.parsing.IssueParser.IssueParserException e) {
            return ProcessingResult.stopped(
                    "STOP: Issue parsing failed",
                    e.getMessage());
        }
    }

    /**
     * Builds a ProcessingContext from pipeline data for loop detection.
     * Aggregates acceptance criteria count and open questions count from available
     * data.
     * 
     * @param parsedIssue             The parsed issue (always available)
     * @param analysisResult          The analysis result (may be null if not yet
     *                                analyzed)
     * @param transformedRequirements The transformed requirements (may be null if
     *                                not yet transformed)
     * @return ProcessingContext populated with available data
     */
    private ProcessingContext buildProcessingContext(
            ParsedIssue parsedIssue,
            AnalysisResult analysisResult,
            TransformedRequirements transformedRequirements) {
        ProcessingContext context = new ProcessingContext();

        // Set acceptance criteria count from transformed requirements if available
        if (transformedRequirements != null) {
            int acceptanceCriteriaCount = transformedRequirements.getAcceptanceCriteria().size()
                    + transformedRequirements.getFunctionalRequirements().size();
            context.setAcceptanceCriteriaCount(acceptanceCriteriaCount);

            // Set open questions count
            context.setOpenQuestionsCount(transformedRequirements.getOpenQuestions().size());
        } else if (analysisResult != null) {
            // Use analysis result data if transformation not yet done
            context.setOpenQuestionsCount(analysisResult.getAmbiguities().size());
        }

        // Check for unsafe inference flags in analysis result
        if (analysisResult != null) {
            // Check requirements for sensitive topics that require human expertise
            checkForUnsafeInference(context, analysisResult);
        }

        return context;
    }

    /**
     * Checks analysis results for topics requiring human expertise and unsafe
     * inference.
     * Sets appropriate flags in the ProcessingContext.
     * 
     * @param context        The processing context to update
     * @param analysisResult The analysis result to check
     */
    private void checkForUnsafeInference(ProcessingContext context, AnalysisResult analysisResult) {
        // Check all requirements for sensitive keywords
        String allRequirementsText = String.join(" ",
                analysisResult.getIntent(),
                String.join(" ", analysisResult.getActors()),
                analysisResult.getFunctionalRequirements().stream()
                        .map(r -> r.getText())
                        .reduce("", String::concat),
                analysisResult.getBusinessRules().stream()
                        .map(r -> r.getText())
                        .reduce("", String::concat))
                .toLowerCase();

        // Detect legal inference requirements
        if (allRequirementsText.contains("legal") || allRequirementsText.contains("compliance")
                || allRequirementsText.contains("regulation") || allRequirementsText.contains("law")) {
            context.setRequiresLegalInference(true);
        }

        // Detect financial inference requirements
        if (allRequirementsText.contains("financial") || allRequirementsText.contains("payment")
                || allRequirementsText.contains("transaction") || allRequirementsText.contains("accounting")) {
            context.setRequiresFinancialInference(true);
        }

        // Detect security inference requirements
        if (allRequirementsText.contains("security") || allRequirementsText.contains("authentication")
                || allRequirementsText.contains("authorization") || allRequirementsText.contains("encrypt")) {
            context.setRequiresSecurityInference(true);
        }

        // Detect regulatory inference requirements
        if (allRequirementsText.contains("regulatory") || allRequirementsText.contains("gdpr")
                || allRequirementsText.contains("hipaa") || allRequirementsText.contains("sox")) {
            context.setRequiresRegulatoryInference(true);
        }
    }

    /**
     * Result of processing a GitHub issue.
     */
    public static class ProcessingResult {
        private final boolean success;
        private final String output;
        private final String stopPhrase;
        private final String reason;

        private ProcessingResult(boolean success, String output, String stopPhrase, String reason) {
            this.success = success;
            this.output = output;
            this.stopPhrase = stopPhrase;
            this.reason = reason;
        }

        public static ProcessingResult success(String output) {
            Objects.requireNonNull(output, "Output cannot be null for success result");
            return new ProcessingResult(true, output, null, null);
        }

        public static ProcessingResult stopped(String stopPhrase, String reason) {
            Objects.requireNonNull(stopPhrase, "Stop phrase cannot be null for stopped result");
            Objects.requireNonNull(reason, "Reason cannot be null for stopped result");
            return new ProcessingResult(false, null, stopPhrase, reason);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getOutput() {
            return output;
        }

        public String getStopPhrase() {
            return stopPhrase;
        }

        public String getReason() {
            return reason;
        }

        @Override
        public String toString() {
            if (success) {
                return "ProcessingResult{success}";
            }
            return String.format("ProcessingResult{stopped, stopPhrase='%s', reason='%s'}",
                    stopPhrase, reason);
        }
    }

    // Getters for testing
    public IssueValidator getValidator() {
        return validator;
    }

    public IssueParser getParser() {
        return parser;
    }

    public RequirementsAnalyzer getAnalyzer() {
        return analyzer;
    }

    public RequirementsTransformer getTransformer() {
        return transformer;
    }

    public OutputGenerator getOutputGenerator() {
        return outputGenerator;
    }

    public LoopDetector getLoopDetector() {
        return loopDetector;
    }

    public StoryConfiguration getConfiguration() {
        return configuration;
    }
}

package com.pos.agent.story;

import com.pos.agent.story.analysis.RequirementsAnalyzer;
import com.pos.agent.story.config.StoryConfiguration;
import com.pos.agent.story.loop.LoopDetector;
import com.pos.agent.story.models.*;
import com.pos.agent.story.output.OutputGenerator;
import com.pos.agent.story.parsing.IssueParser;
import com.pos.agent.story.transformation.RequirementsTransformer;
import com.pos.agent.story.validation.IssueValidator;

import java.util.Objects;

/**
 * Main orchestrator for the Story Strengthening Agent pipeline.
 * Coordinates validation, parsing, analysis, transformation, and output generation.
 * 
 * This class follows the reference pattern from workspace-agents/audit/MissingIssuesAuditSystem.
 * 
 * Requirements: All requirements
 */
public class StoryStrengtheningAgent {
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
     * @param validator Issue validator
     * @param parser Issue parser
     * @param analyzer Requirements analyzer
     * @param transformer Requirements transformer
     * @param outputGenerator Output generator
     * @param loopDetector Loop detector
     * @param configuration Agent configuration
     */
    public StoryStrengtheningAgent(
            IssueValidator validator,
            IssueParser parser,
            RequirementsAnalyzer analyzer,
            RequirementsTransformer transformer,
            OutputGenerator outputGenerator,
            LoopDetector loopDetector,
            StoryConfiguration configuration) {
        this.validator = Objects.requireNonNull(validator, "Validator cannot be null");
        this.parser = Objects.requireNonNull(parser, "Parser cannot be null");
        this.analyzer = Objects.requireNonNull(analyzer, "Analyzer cannot be null");
        this.transformer = Objects.requireNonNull(transformer, "Transformer cannot be null");
        this.outputGenerator = Objects.requireNonNull(outputGenerator, "Output generator cannot be null");
        this.loopDetector = Objects.requireNonNull(loopDetector, "Loop detector cannot be null");
        this.configuration = Objects.requireNonNull(configuration, "Configuration cannot be null");
    }

    /**
     * Processes a GitHub issue through the complete pipeline.
     * Returns the strengthened requirements document or a stop phrase if processing cannot continue.
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
                    validationResult.getReason().orElse("Unknown reason")
                );
            }

            // Step 2: Parsing
            ParsedIssue parsedIssue = parser.parseIssue(issue);

            // Step 3: Analysis
            AnalysisResult analysisResult = analyzer.analyzeRequirements(parsedIssue);

            // Step 4: Transformation
            TransformedRequirements transformedRequirements = transformer.transformRequirements(analysisResult);

            // Step 5: Output Generation
            String output = outputGenerator.generateOutput(transformedRequirements, issue.getBody());

            return ProcessingResult.success(output);
            
        } catch (com.pos.agent.story.parsing.IssueParser.IssueParserException e) {
            return ProcessingResult.stopped(
                "STOP: Issue parsing failed",
                e.getMessage()
            );
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

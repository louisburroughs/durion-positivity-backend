package com.pos.agent.story.transformation;

import com.pos.agent.story.analysis.OpenQuestionGenerator;
import com.pos.agent.story.models.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Default implementation of RequirementsTransformer.
 * Delegates to EarsPatternTransformer and GherkinScenarioGenerator.
 * 
 * Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 6.1, 6.2, 6.3, 6.4, 6.5, 7.1, 7.2, 7.3, 7.4, 7.6
 */
public class DefaultRequirementsTransformer implements RequirementsTransformer {
    
    private final EarsPatternTransformer earsTransformer;
    private final GherkinScenarioGenerator gherkinGenerator;
    private final OpenQuestionGenerator questionGenerator;
    
    /**
     * Creates a new DefaultRequirementsTransformer with default components.
     */
    public DefaultRequirementsTransformer() {
        this.earsTransformer = new EarsPatternTransformer();
        this.gherkinGenerator = new GherkinScenarioGenerator();
        this.questionGenerator = new OpenQuestionGenerator();
    }
    
    /**
     * Creates a new DefaultRequirementsTransformer with custom components.
     * 
     * @param earsTransformer EARS pattern transformer
     * @param gherkinGenerator Gherkin scenario generator
     * @param questionGenerator Open question generator
     */
    public DefaultRequirementsTransformer(
            EarsPatternTransformer earsTransformer,
            GherkinScenarioGenerator gherkinGenerator,
            OpenQuestionGenerator questionGenerator) {
        this.earsTransformer = Objects.requireNonNull(earsTransformer, "EARS transformer cannot be null");
        this.gherkinGenerator = Objects.requireNonNull(gherkinGenerator, "Gherkin generator cannot be null");
        this.questionGenerator = Objects.requireNonNull(questionGenerator, "Question generator cannot be null");
    }
    
    @Override
    public TransformedRequirements transformRequirements(AnalysisResult analysis) {
        Objects.requireNonNull(analysis, "Analysis result cannot be null");
        
        // Generate header from issue metadata
        String header = "Story Strengthening Analysis";
        
        // Transform functional requirements into Gherkin scenarios
        List<GherkinScenario> functionalRequirementScenarios = new ArrayList<>();
        for (Requirement req : analysis.getFunctionalRequirements()) {
            GherkinScenario scenario = gherkinGenerator.generateScenario(req);
            functionalRequirementScenarios.add(scenario);
        }
        
        // Transform business rules to strings (EARS formatted)
        List<String> transformedBusinessRules = new ArrayList<>();
        for (Requirement rule : analysis.getBusinessRules()) {
            String transformedText = earsTransformer.transformRequirement(rule);
            transformedBusinessRules.add(transformedText);
        }
        
        // Transform preconditions to strings (EARS formatted)
        List<String> preconditionStrings = new ArrayList<>();
        for (Requirement precondition : analysis.getPreconditions()) {
            String transformedText = earsTransformer.transformRequirement(precondition);
            preconditionStrings.add(transformedText);
        }
        
        // Generate acceptance criteria as Gherkin scenarios
        List<GherkinScenario> acceptanceCriteria = new ArrayList<>(functionalRequirementScenarios);
        
        // Convert data requirements to strings
        List<String> dataRequirementStrings = new ArrayList<>();
        for (AnalysisResult.DataRequirement dataReq : analysis.getDataRequirements()) {
            String reqStr = dataReq.getFieldName() + ": " + dataReq.getDescription();
            if (dataReq.isRequired()) {
                reqStr += " (required)";
            }
            dataRequirementStrings.add(reqStr);
        }
        
        // Generate open questions from ambiguities
        List<OpenQuestion> openQuestions = new ArrayList<>(analysis.getAmbiguities());
        
        return new TransformedRequirements(
            header,
            analysis.getIntent(),
            analysis.getActors(),
            preconditionStrings,
            functionalRequirementScenarios,
            new ArrayList<>(), // Alternate flows - empty for now
            transformedBusinessRules,
            dataRequirementStrings,
            acceptanceCriteria,
            new ArrayList<>(), // Observability requirements - empty for now
            openQuestions
        );
    }
}

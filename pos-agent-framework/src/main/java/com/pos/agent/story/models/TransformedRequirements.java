package com.pos.agent.story.models;

import java.util.List;
import java.util.Objects;

/**
 * Represents the transformed requirements ready for output generation.
 * Contains all sections in the proper format (EARS, Gherkin).
 * 
 * Requirements: 3.5, 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 4.9, 4.10, 4.11, 4.12
 */
public class TransformedRequirements {
    private final String header;
    private final String intent;
    private final List<String> actors;
    private final List<String> preconditions;
    private final List<GherkinScenario> functionalRequirements;
    private final List<String> alternateFlows;
    private final List<String> businessRules;
    private final List<String> dataRequirements;
    private final List<GherkinScenario> acceptanceCriteria;
    private final List<String> observability;
    private final List<OpenQuestion> openQuestions;

    public TransformedRequirements(
            String header,
            String intent,
            List<String> actors,
            List<String> preconditions,
            List<GherkinScenario> functionalRequirements,
            List<String> alternateFlows,
            List<String> businessRules,
            List<String> dataRequirements,
            List<GherkinScenario> acceptanceCriteria,
            List<String> observability,
            List<OpenQuestion> openQuestions) {
        this.header = Objects.requireNonNull(header, "Header cannot be null");
        this.intent = Objects.requireNonNull(intent, "Intent cannot be null");
        this.actors = Objects.requireNonNull(actors, "Actors cannot be null");
        this.preconditions = Objects.requireNonNull(preconditions, "Preconditions cannot be null");
        this.functionalRequirements = Objects.requireNonNull(functionalRequirements, "Functional requirements cannot be null");
        this.alternateFlows = Objects.requireNonNull(alternateFlows, "Alternate flows cannot be null");
        this.businessRules = Objects.requireNonNull(businessRules, "Business rules cannot be null");
        this.dataRequirements = Objects.requireNonNull(dataRequirements, "Data requirements cannot be null");
        this.acceptanceCriteria = Objects.requireNonNull(acceptanceCriteria, "Acceptance criteria cannot be null");
        this.observability = Objects.requireNonNull(observability, "Observability cannot be null");
        this.openQuestions = Objects.requireNonNull(openQuestions, "Open questions cannot be null");
    }

    public String getHeader() {
        return header;
    }

    public String getIntent() {
        return intent;
    }

    public List<String> getActors() {
        return actors;
    }

    public List<String> getPreconditions() {
        return preconditions;
    }

    public List<GherkinScenario> getFunctionalRequirements() {
        return functionalRequirements;
    }

    public List<String> getAlternateFlows() {
        return alternateFlows;
    }

    public List<String> getBusinessRules() {
        return businessRules;
    }

    public List<String> getDataRequirements() {
        return dataRequirements;
    }

    public List<GherkinScenario> getAcceptanceCriteria() {
        return acceptanceCriteria;
    }

    public List<String> getObservability() {
        return observability;
    }

    public List<OpenQuestion> getOpenQuestions() {
        return openQuestions;
    }

    @Override
    public String toString() {
        return String.format("TransformedRequirements{scenarios=%d, openQuestions=%d}", 
                           functionalRequirements.size() + acceptanceCriteria.size(), 
                           openQuestions.size());
    }
}

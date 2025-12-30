package com.pos.agent.story.models;

import java.util.List;
import java.util.Objects;

/**
 * Result of analyzing issue content to identify requirements elements.
 * Contains all extracted information from the analysis stage.
 * 
 * Requirements: 3.1, 7.1, 7.3, 8.1, 8.2, 8.3, 8.4, 8.5
 */
public class AnalysisResult {
    private final String intent;
    private final List<String> actors;
    private final List<String> stakeholders;
    private final List<Requirement> preconditions;
    private final List<Requirement> functionalRequirements;
    private final List<Requirement> errorFlows;
    private final List<Requirement> businessRules;
    private final List<DataRequirement> dataRequirements;
    private final List<OpenQuestion> ambiguities;

    public AnalysisResult(
            String intent,
            List<String> actors,
            List<String> stakeholders,
            List<Requirement> preconditions,
            List<Requirement> functionalRequirements,
            List<Requirement> errorFlows,
            List<Requirement> businessRules,
            List<DataRequirement> dataRequirements,
            List<OpenQuestion> ambiguities) {
        this.intent = Objects.requireNonNull(intent, "Intent cannot be null");
        this.actors = Objects.requireNonNull(actors, "Actors cannot be null");
        this.stakeholders = Objects.requireNonNull(stakeholders, "Stakeholders cannot be null");
        this.preconditions = Objects.requireNonNull(preconditions, "Preconditions cannot be null");
        this.functionalRequirements = Objects.requireNonNull(functionalRequirements, "Functional requirements cannot be null");
        this.errorFlows = Objects.requireNonNull(errorFlows, "Error flows cannot be null");
        this.businessRules = Objects.requireNonNull(businessRules, "Business rules cannot be null");
        this.dataRequirements = Objects.requireNonNull(dataRequirements, "Data requirements cannot be null");
        this.ambiguities = Objects.requireNonNull(ambiguities, "Ambiguities cannot be null");
    }

    public String getIntent() {
        return intent;
    }

    public List<String> getActors() {
        return actors;
    }

    public List<String> getStakeholders() {
        return stakeholders;
    }

    public List<Requirement> getPreconditions() {
        return preconditions;
    }

    public List<Requirement> getFunctionalRequirements() {
        return functionalRequirements;
    }

    public List<Requirement> getErrorFlows() {
        return errorFlows;
    }

    public List<Requirement> getBusinessRules() {
        return businessRules;
    }

    public List<DataRequirement> getDataRequirements() {
        return dataRequirements;
    }

    public List<OpenQuestion> getAmbiguities() {
        return ambiguities;
    }

    /**
     * Represents a data requirement identified in the issue.
     */
    public static class DataRequirement {
        private final String fieldName;
        private final String description;
        private final boolean isRequired;

        public DataRequirement(String fieldName, String description, boolean isRequired) {
            this.fieldName = Objects.requireNonNull(fieldName, "Field name cannot be null");
            this.description = Objects.requireNonNull(description, "Description cannot be null");
            this.isRequired = isRequired;
        }

        public String getFieldName() {
            return fieldName;
        }

        public String getDescription() {
            return description;
        }

        public boolean isRequired() {
            return isRequired;
        }

        @Override
        public String toString() {
            return String.format("DataRequirement{field='%s', required=%s}", 
                               fieldName, isRequired);
        }
    }

    @Override
    public String toString() {
        return String.format("AnalysisResult{actors=%d, requirements=%d, ambiguities=%d}", 
                           actors.size(), functionalRequirements.size(), ambiguities.size());
    }
}

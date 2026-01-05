package com.pos.agent.story.output;

import com.pos.agent.story.models.GherkinScenario;
import com.pos.agent.story.models.OpenQuestion;
import com.pos.agent.story.models.TransformedRequirements;

import java.util.List;

/**
 * Generates the final markdown output with mandatory section ordering.
 * 
 * Implements the mandatory section ordering:
 * 1. Header (normalized metadata)
 * 2. Intent statement
 * 3. Actors and stakeholders
 * 4. Preconditions (EARS state-driven)
 * 5. Functional requirements (Gherkin)
 * 6. Alternate and error flows (EARS event-driven)
 * 7. Business rules (EARS unconditional)
 * 8. Data requirements
 * 9. Acceptance criteria (Gherkin)
 * 10. Observability and audit requirements
 * 11. Open questions (if present)
 * 12. Original story (verbatim)
 * 
 * Requirements: 3.3, 3.4, 3.5, 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 4.9, 4.10, 4.11, 4.12, 9.1, 9.2, 9.3
 */
public class OutputGenerator {
    
    private static final String SECTION_SEPARATOR = "\n\n";
    private static final String ORIGINAL_STORY_TITLE = "## Original Story (Unmodified – For Traceability)";
    
    /**
     * Generates the complete markdown output.
     * 
     * @param transformed Transformed requirements with all sections
     * @param originalStory Original issue body text (verbatim)
     * @return Complete markdown output
     */
    public String generateOutput(TransformedRequirements transformed, String originalStory) {
        if (transformed == null) {
            throw new IllegalArgumentException("Transformed requirements cannot be null");
        }
        if (originalStory == null) {
            throw new IllegalArgumentException("Original story cannot be null");
        }
        
        StringBuilder output = new StringBuilder();
        
        // 1. Header (normalized metadata)
        appendSection(output, transformed.getHeader());
        
        // 2. Intent statement
        appendSection(output, "## Intent", transformed.getIntent());
        
        // 3. Actors and stakeholders
        if (!transformed.getActors().isEmpty()) {
            appendSection(output, "## Actors and Stakeholders", formatList(transformed.getActors()));
        }
        
        // 4. Preconditions (EARS state-driven)
        if (!transformed.getPreconditions().isEmpty()) {
            appendSection(output, "## Preconditions", formatList(transformed.getPreconditions()));
        }
        
        // 5. Functional requirements (Gherkin)
        if (!transformed.getFunctionalRequirements().isEmpty()) {
            appendSection(output, "## Functional Requirements", 
                         formatGherkinScenarios(transformed.getFunctionalRequirements()));
        }
        
        // 6. Alternate and error flows (EARS event-driven)
        if (!transformed.getAlternateFlows().isEmpty()) {
            appendSection(output, "## Alternate and Error Flows", 
                         formatList(transformed.getAlternateFlows()));
        }
        
        // 7. Business rules (EARS unconditional)
        if (!transformed.getBusinessRules().isEmpty()) {
            appendSection(output, "## Business Rules", formatList(transformed.getBusinessRules()));
        }
        
        // 8. Data requirements
        if (!transformed.getDataRequirements().isEmpty()) {
            appendSection(output, "## Data Requirements", formatList(transformed.getDataRequirements()));
        }
        
        // 9. Acceptance criteria (Gherkin)
        if (!transformed.getAcceptanceCriteria().isEmpty()) {
            appendSection(output, "## Acceptance Criteria", 
                         formatGherkinScenarios(transformed.getAcceptanceCriteria()));
        }
        
        // 10. Observability and audit requirements
        if (!transformed.getObservability().isEmpty()) {
            appendSection(output, "## Observability and Audit Requirements", 
                         formatList(transformed.getObservability()));
        }
        
        // 11. Open questions (if present)
        if (!transformed.getOpenQuestions().isEmpty()) {
            appendSection(output, "## Open Questions", formatOpenQuestions(transformed.getOpenQuestions()));
        }
        
        // 12. Original story (verbatim)
        appendSection(output, ORIGINAL_STORY_TITLE, originalStory);
        
        return output.toString();
    }
    
    /**
     * Appends a section with just content (no title).
     * 
     * @param output StringBuilder to append to
     * @param content Section content
     */
    private void appendSection(StringBuilder output, String content) {
        if (content != null && !content.trim().isEmpty()) {
            output.append(content.trim()).append(SECTION_SEPARATOR);
        }
    }
    
    /**
     * Appends a section with title and content.
     * 
     * @param output StringBuilder to append to
     * @param title Section title
     * @param content Section content
     */
    private void appendSection(StringBuilder output, String title, String content) {
        // Always append title for original story section, even if content is empty
        if (title.equals(ORIGINAL_STORY_TITLE)) {
            output.append(title).append("\n\n");
            // Don't trim original story - preserve it verbatim
            if (content != null) {
                output.append(content).append(SECTION_SEPARATOR);
            } else {
                output.append(SECTION_SEPARATOR);
            }
        } else if (content != null && !content.trim().isEmpty()) {
            output.append(title).append("\n\n");
            output.append(content.trim()).append(SECTION_SEPARATOR);
        }
    }
    
    /**
     * Formats a list of strings as markdown bullet points.
     * 
     * @param items List of items
     * @return Formatted markdown list
     */
    private String formatList(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        
        StringBuilder formatted = new StringBuilder();
        for (String item : items) {
            if (item != null && !item.trim().isEmpty()) {
                formatted.append("- ").append(item.trim()).append("\n");
            }
        }
        return formatted.toString().trim();
    }
    
    /**
     * Formats Gherkin scenarios as markdown.
     * 
     * @param scenarios List of Gherkin scenarios
     * @return Formatted markdown scenarios
     */
    private String formatGherkinScenarios(List<GherkinScenario> scenarios) {
        if (scenarios == null || scenarios.isEmpty()) {
            return "";
        }
        
        StringBuilder formatted = new StringBuilder();
        for (int i = 0; i < scenarios.size(); i++) {
            GherkinScenario scenario = scenarios.get(i);
            formatted.append(formatGherkinScenario(scenario));
            
            // Add separator between scenarios (but not after the last one)
            if (i < scenarios.size() - 1) {
                formatted.append("\n\n");
            }
        }
        return formatted.toString();
    }
    
    /**
     * Formats a single Gherkin scenario.
     * 
     * @param scenario Gherkin scenario
     * @return Formatted markdown scenario
     */
    private String formatGherkinScenario(GherkinScenario scenario) {
        StringBuilder formatted = new StringBuilder();
        
        // Scenario name
        formatted.append("**Scenario:** ").append(scenario.getScenario()).append("\n\n");
        
        // Given clauses
        if (!scenario.getGiven().isEmpty()) {
            for (int i = 0; i < scenario.getGiven().size(); i++) {
                String keyword = i == 0 ? "Given" : "And";
                formatted.append("- **").append(keyword).append("** ").append(scenario.getGiven().get(i)).append("\n");
            }
        }
        
        // When clauses
        if (!scenario.getWhen().isEmpty()) {
            for (int i = 0; i < scenario.getWhen().size(); i++) {
                String keyword = i == 0 ? "When" : "And";
                formatted.append("- **").append(keyword).append("** ").append(scenario.getWhen().get(i)).append("\n");
            }
        }
        
        // Then clauses
        if (!scenario.getThen().isEmpty()) {
            for (int i = 0; i < scenario.getThen().size(); i++) {
                String keyword = i == 0 ? "Then" : "And";
                formatted.append("- **").append(keyword).append("** ").append(scenario.getThen().get(i)).append("\n");
            }
        }
        
        return formatted.toString().trim();
    }
    
    /**
     * Formats open questions as markdown.
     * 
     * @param questions List of open questions
     * @return Formatted markdown questions
     */
    private String formatOpenQuestions(List<OpenQuestion> questions) {
        if (questions == null || questions.isEmpty()) {
            return "";
        }
        
        StringBuilder formatted = new StringBuilder();
        for (int i = 0; i < questions.size(); i++) {
            OpenQuestion question = questions.get(i);
            formatted.append(formatOpenQuestion(question));
            
            // Add separator between questions (but not after the last one)
            if (i < questions.size() - 1) {
                formatted.append("\n\n---\n\n");
            }
        }
        return formatted.toString();
    }
    
    /**
     * Formats a single open question.
     * 
     * @param question Open question
     * @return Formatted markdown question
     */
    private String formatOpenQuestion(OpenQuestion question) {
        StringBuilder formatted = new StringBuilder();
        formatted.append("**Question:** ").append(question.getQuestion()).append("\n\n");
        formatted.append("**Why it matters:** ").append(question.getWhyItMatters()).append("\n\n");
        formatted.append("**Impact:** ").append(question.getImpact());
        return formatted.toString();
    }
    
    /**
     * Validates that the output contains all mandatory sections in the correct order.
     * 
     * @param output Generated output
     * @return true if valid, false otherwise
     */
    public boolean validateOutput(String output) {
        if (output == null || output.trim().isEmpty()) {
            return false;
        }
        
        // Check that original story section is present and at the end
        if (!output.contains(ORIGINAL_STORY_TITLE)) {
            return false;
        }
        
        int originalStoryIndex = output.indexOf(ORIGINAL_STORY_TITLE);
        
        // Check that original story is the last section (no other ## headers after it)
        String afterOriginalStory = output.substring(originalStoryIndex + ORIGINAL_STORY_TITLE.length());
        if (afterOriginalStory.contains("\n## ")) {
            return false;
        }
        
        return true;
    }
}

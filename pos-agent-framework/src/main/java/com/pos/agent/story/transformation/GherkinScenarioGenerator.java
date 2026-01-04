package com.pos.agent.story.transformation;

import com.pos.agent.story.models.GherkinScenario;
import com.pos.agent.story.models.Requirement;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Generates Gherkin scenarios from requirements.
 * 
 * Implements:
 * - Given/When/Then/And keyword usage with proper formatting
 * - Verifiable Then clause generation (must be testable)
 * - Compound condition avoidance (split into multiple clauses)
 * - Prose-free Gherkin block generation (structured format only)
 * - Modal verb filtering (should, may, might, could, ideally)
 * 
 * Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6
 */
public class GherkinScenarioGenerator {

    // Modal verbs that should be filtered out
    private static final Set<String> MODAL_VERBS = Set.of(
        "should", "may", "might", "could", "would", "ideally", "possibly", "perhaps"
    );
    
    // Narrative phrases that should be filtered out (prose indicators)
    private static final Set<String> NARRATIVE_PHRASES = Set.of(
        "in order to", "as mentioned", "note that", "for example", "it should be noted",
        "please note", "keep in mind", "bear in mind", "it is important to"
    );
    
    // Patterns for extracting Gherkin components
    private static final Pattern GIVEN_PATTERN = Pattern.compile(
        "(?:given|assuming|provided that)\\s+(.+?)(?:,|\\.|$)",
        Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern WHEN_PATTERN = Pattern.compile(
        "(?:when|if|upon|on|after)\\s+(.+?)(?:,|\\.|$)",
        Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern THEN_PATTERN = Pattern.compile(
        "(?:then|the system|it)\\s+(.+?)(?:,|\\.|$)",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Generates a Gherkin scenario from a requirement.
     * 
     * @param requirement The requirement to convert
     * @return A GherkinScenario with Given/When/Then structure
     * Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6
     */
    public GherkinScenario generateScenario(Requirement requirement) {
        if (requirement == null) {
            throw new IllegalArgumentException("Requirement cannot be null");
        }
        
        String text = requirement.getText();
        String scenarioName = generateScenarioName(text);
        
        // Extract Given/When/Then components
        List<String> givenClauses = extractGivenClauses(text);
        List<String> whenClauses = extractWhenClauses(text);
        List<String> thenClauses = extractThenClauses(text);
        
        // Filter narrative phrases from all clauses
        givenClauses = filterNarrativePhrases(givenClauses);
        whenClauses = filterNarrativePhrases(whenClauses);
        thenClauses = filterNarrativePhrases(thenClauses);
        
        // Filter modal verbs from all clauses
        givenClauses = filterModalVerbs(givenClauses);
        whenClauses = filterModalVerbs(whenClauses);
        thenClauses = filterModalVerbs(thenClauses);
        
        // Ensure verifiable Then clauses
        thenClauses = ensureVerifiableThenClauses(thenClauses);
        
        // Split compound conditions
        givenClauses = splitCompoundConditions(givenClauses);
        whenClauses = splitCompoundConditions(whenClauses);
        thenClauses = splitCompoundConditions(thenClauses);
        
        return new GherkinScenario(scenarioName, givenClauses, whenClauses, thenClauses);
    }

    /**
     * Generates multiple Gherkin scenarios from a list of requirements.
     * 
     * @param requirements The requirements to convert
     * @return List of GherkinScenarios
     * Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6
     */
    public List<GherkinScenario> generateScenarios(List<Requirement> requirements) {
        if (requirements == null) {
            throw new IllegalArgumentException("Requirements list cannot be null");
        }
        
        return requirements.stream()
                .map(this::generateScenario)
                .collect(Collectors.toList());
    }

    /**
     * Generates a scenario name from requirement text.
     * Creates a concise, descriptive name for the scenario.
     * 
     * Requirements: 5.1, 5.5, 5.6
     */
    private String generateScenarioName(String text) {
        // Remove narrative phrases first
        text = removeNarrativePhrases(text);
        
        // Remove common prefixes
        text = text.replaceFirst("(?i)^(the system shall|the system must|system shall|system must)\\s+", "");
        text = text.replaceFirst("(?i)^(when|if|given|while)\\s+", "");
        
        // Take first clause or sentence
        String[] parts = text.split("[,.]");
        String name = parts[0].trim();
        
        // Filter modal verbs from scenario name
        name = removeModalVerbs(name);
        
        // Capitalize first letter
        if (!name.isEmpty()) {
            name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
        
        // Limit length
        if (name.length() > 80) {
            name = name.substring(0, 77) + "...";
        }
        
        return name;
    }

    /**
     * Extracts Given clauses from requirement text.
     * Given clauses represent preconditions or initial state.
     * 
     * Requirements: 5.1, 5.3
     */
    private List<String> extractGivenClauses(String text) {
        List<String> clauses = new ArrayList<>();
        
        Matcher matcher = GIVEN_PATTERN.matcher(text);
        while (matcher.find()) {
            String clause = matcher.group(1).trim();
            if (!clause.isEmpty()) {
                clauses.add(normalizeClause(clause));
            }
        }
        
        // If no explicit Given found, check for state-related patterns
        if (clauses.isEmpty()) {
            if (text.toLowerCase().matches(".*\\b(authenticated|logged in|authorized|active)\\b.*")) {
                // Extract implied precondition
                String[] words = text.split("\\s+");
                for (int i = 0; i < words.length - 1; i++) {
                    if (words[i].toLowerCase().matches("(authenticated|logged|authorized|active)")) {
                        clauses.add("the user is " + words[i].toLowerCase());
                        break;
                    }
                }
            }
        }
        
        return clauses;
    }

    /**
     * Extracts When clauses from requirement text.
     * When clauses represent actions or events.
     * 
     * Requirements: 5.1, 5.3
     */
    private List<String> extractWhenClauses(String text) {
        List<String> clauses = new ArrayList<>();
        
        Matcher matcher = WHEN_PATTERN.matcher(text);
        while (matcher.find()) {
            String clause = matcher.group(1).trim();
            if (!clause.isEmpty()) {
                clauses.add(normalizeClause(clause));
            }
        }
        
        // If no explicit When found, try to extract action
        if (clauses.isEmpty()) {
            // Look for action verbs
            String normalized = text.toLowerCase();
            if (normalized.contains("user") || normalized.contains("system")) {
                // Extract the main action
                String action = extractMainAction(text);
                if (!action.isEmpty()) {
                    clauses.add(action);
                }
            }
        }
        
        return clauses;
    }

    /**
     * Extracts Then clauses from requirement text.
     * Then clauses represent expected outcomes or results.
     * 
     * Requirements: 5.1, 5.2, 5.3
     */
    private List<String> extractThenClauses(String text) {
        List<String> clauses = new ArrayList<>();
        
        Matcher matcher = THEN_PATTERN.matcher(text);
        while (matcher.find()) {
            String clause = matcher.group(1).trim();
            if (!clause.isEmpty()) {
                clauses.add(normalizeClause(clause));
            }
        }
        
        // If no explicit Then found, use the main requirement as outcome
        if (clauses.isEmpty()) {
            String outcome = extractOutcome(text);
            if (!outcome.isEmpty()) {
                clauses.add(outcome);
            }
        }
        
        return clauses;
    }

    /**
     * Extracts the main action from requirement text.
     */
    private String extractMainAction(String text) {
        // Remove common prefixes
        text = text.replaceFirst("(?i)^(the system shall|the system must|system shall|system must)\\s+", "");
        text = text.replaceFirst("(?i)^(when|if|given|while)\\s+", "");
        
        // Take first clause
        String[] parts = text.split("[,.]");
        return parts.length > 0 ? normalizeClause(parts[0]) : "";
    }

    /**
     * Extracts the expected outcome from requirement text.
     */
    private String extractOutcome(String text) {
        // Remove prefixes and get the main action/outcome
        text = text.replaceFirst("(?i)^(the system shall|the system must|system shall|system must)\\s+", "");
        text = text.replaceFirst("(?i)^(when|if|given|while)\\s+.+?,\\s*", "");
        
        String[] parts = text.split("[,.]");
        return parts.length > 0 ? normalizeClause(parts[0]) : text;
    }

    /**
     * Normalizes a clause by removing extra whitespace and ensuring proper format.
     */
    private String normalizeClause(String clause) {
        clause = clause.trim();
        clause = clause.replaceAll("\\s+", " ");
        
        // Ensure lowercase start (will be prefixed with Given/When/Then)
        if (!clause.isEmpty()) {
            clause = Character.toLowerCase(clause.charAt(0)) + clause.substring(1);
        }
        
        // Remove trailing punctuation
        clause = clause.replaceAll("[,;:]$", "");
        
        return clause;
    }

    /**
     * Filters modal verbs from clauses.
     * Modal verbs like "should", "may", "might" make requirements non-testable.
     * 
     * Requirements: 5.6
     */
    private List<String> filterModalVerbs(List<String> clauses) {
        return clauses.stream()
                .map(this::removeModalVerbs)
                .collect(Collectors.toList());
    }

    /**
     * Filters narrative phrases from clauses.
     * Narrative phrases are prose indicators that should not appear in structured Gherkin.
     * 
     * Requirements: 5.5
     */
    private List<String> filterNarrativePhrases(List<String> clauses) {
        return clauses.stream()
                .map(this::removeNarrativePhrases)
                .filter(clause -> !clause.trim().isEmpty()) // Remove empty clauses after filtering
                .collect(Collectors.toList());
    }

    /**
     * Removes modal verbs from a single clause.
     */
    private String removeModalVerbs(String clause) {
        for (String modalVerb : MODAL_VERBS) {
            // Remove modal verb with surrounding whitespace
            clause = clause.replaceAll("(?i)\\b" + modalVerb + "\\b\\s*", "");
        }
        return clause.trim();
    }

    /**
     * Removes narrative phrases from text.
     * Narrative phrases like "In order to", "Note that" are prose indicators
     * that should not appear in structured Gherkin scenarios.
     * 
     * Requirements: 5.5
     */
    private String removeNarrativePhrases(String text) {
        for (String phrase : NARRATIVE_PHRASES) {
            // Remove narrative phrase with surrounding whitespace and punctuation
            text = text.replaceAll("(?i)\\b" + phrase + "\\b[,\\s]*", "");
        }
        return text.trim();
    }

    /**
     * Ensures Then clauses are verifiable (testable).
     * Verifiable clauses have concrete, measurable outcomes.
     * 
     * Requirements: 5.2
     */
    private List<String> ensureVerifiableThenClauses(List<String> thenClauses) {
        return thenClauses.stream()
                .map(this::makeVerifiable)
                .collect(Collectors.toList());
    }

    /**
     * Makes a Then clause verifiable by ensuring it has a concrete outcome.
     */
    private String makeVerifiable(String clause) {
        // If clause is vague, try to make it more concrete
        String lower = clause.toLowerCase();
        
        // Replace vague terms with concrete ones
        if (lower.contains("successfully")) {
            // Already somewhat concrete
            return clause;
        }
        
        if (lower.contains("correctly") || lower.contains("properly")) {
            // These are vague - but keep them for now as they may be refined later
            return clause;
        }
        
        // Ensure clause describes a state or action result
        if (!lower.matches(".*(is|are|has|have|displays|shows|returns|creates|updates|deletes|sends|receives).*")) {
            // Add a concrete verb if missing
            if (!clause.isEmpty()) {
                clause = "the system " + clause;
            }
        }
        
        return clause;
    }

    /**
     * Splits compound conditions into multiple simple clauses.
     * Compound conditions with "and" or "or" should be separate clauses.
     * 
     * Requirements: 5.4
     */
    private List<String> splitCompoundConditions(List<String> clauses) {
        List<String> split = new ArrayList<>();
        
        for (String clause : clauses) {
            // Split on "and" but not "and then"
            if (clause.toLowerCase().contains(" and ") && !clause.toLowerCase().contains(" and then")) {
                String[] parts = clause.split("(?i)\\s+and\\s+");
                for (String part : parts) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty()) {
                        split.add(trimmed);
                    }
                }
            } else {
                split.add(clause);
            }
        }
        
        return split;
    }

    /**
     * Formats a Gherkin scenario as a string.
     * Produces structured Gherkin format without prose.
     * 
     * Requirements: 5.1, 5.5
     */
    public String formatScenario(GherkinScenario scenario) {
        if (scenario == null) {
            throw new IllegalArgumentException("Scenario cannot be null");
        }
        
        StringBuilder sb = new StringBuilder();
        
        // Scenario name
        sb.append("Scenario: ").append(scenario.getScenario()).append("\n");
        
        // Given clauses
        List<String> givenClauses = scenario.getGiven();
        if (!givenClauses.isEmpty()) {
            sb.append("  Given ").append(givenClauses.get(0)).append("\n");
            for (int i = 1; i < givenClauses.size(); i++) {
                sb.append("  And ").append(givenClauses.get(i)).append("\n");
            }
        }
        
        // When clauses
        List<String> whenClauses = scenario.getWhen();
        if (!whenClauses.isEmpty()) {
            sb.append("  When ").append(whenClauses.get(0)).append("\n");
            for (int i = 1; i < whenClauses.size(); i++) {
                sb.append("  And ").append(whenClauses.get(i)).append("\n");
            }
        }
        
        // Then clauses
        List<String> thenClauses = scenario.getThen();
        if (!thenClauses.isEmpty()) {
            sb.append("  Then ").append(thenClauses.get(0)).append("\n");
            for (int i = 1; i < thenClauses.size(); i++) {
                sb.append("  And ").append(thenClauses.get(i)).append("\n");
            }
        }
        
        return sb.toString();
    }
}

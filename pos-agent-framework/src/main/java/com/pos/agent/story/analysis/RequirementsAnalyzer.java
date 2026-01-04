package com.pos.agent.story.analysis;

import com.pos.agent.story.models.*;
import com.pos.agent.story.models.AnalysisResult.DataRequirement;
import com.pos.agent.story.models.Requirement.EarsPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Analyzes parsed issue content to identify requirements elements.
 * 
 * This component:
 * - Identifies actors and stakeholders using pattern matching
 * - Extracts business intent from issue body
 * - Detects preconditions and state dependencies
 * - Identifies functional requirements
 * - Detects error flows and edge cases
 * - Extracts business rules
 * - Identifies data requirements
 * - Flags ambiguities and uncertainties
 * 
 * Requirements: 3.1, 7.1, 7.3, 8.1, 8.2, 8.3, 8.4, 8.5
 */
public class RequirementsAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(RequirementsAnalyzer.class);
    
    // Actor identification patterns
    private static final Pattern ACTOR_PATTERN = Pattern.compile(
        "(?:as (?:a|an) )([a-zA-Z][a-zA-Z0-9 ]+?)(?:,| I | so )",
        Pattern.CASE_INSENSITIVE
    );
    
    // Intent extraction patterns
    private static final Pattern INTENT_PATTERN = Pattern.compile(
        "(?:I want to|I need to|I can|should be able to|must be able to) ([^,\\.]+)",
        Pattern.CASE_INSENSITIVE
    );
    
    // Precondition patterns
    private static final Pattern PRECONDITION_PATTERN = Pattern.compile(
        "(?:given|when|if|while|before|after|assuming) ([^,\\.]+)",
        Pattern.CASE_INSENSITIVE
    );
    
    // Data field patterns
    private static final Pattern DATA_FIELD_PATTERN = Pattern.compile(
        "(?:field|attribute|property|data|column)\\s*:?\\s*([a-zA-Z][a-zA-Z0-9_]*)",
        Pattern.CASE_INSENSITIVE
    );
    
    // Vague terms that indicate ambiguity
    private static final Set<String> VAGUE_TERMS = Set.of(
        "quickly", "slowly", "fast", "slow", "adequate", "reasonable",
        "user-friendly", "easy", "simple", "appropriate", "suitable",
        "efficient", "optimal", "good", "bad", "nice", "better"
    );
    
    // Keywords indicating error flows
    private static final Set<String> ERROR_KEYWORDS = Set.of(
        "error", "fail", "failure", "exception", "invalid", "reject",
        "denied", "unauthorized", "forbidden", "timeout", "unavailable"
    );
    
    // Keywords indicating state/preconditions
    private static final Set<String> STATE_KEYWORDS = Set.of(
        "state", "status", "condition", "prerequisite", "before",
        "after", "during", "while", "when", "if"
    );

    /**
     * Analyzes a parsed issue to extract requirements elements.
     * 
     * @param parsedIssue The parsed issue to analyze
     * @return AnalysisResult containing all extracted requirements elements
     * 
     * Requirements: 3.1, 7.1, 7.3, 8.1, 8.2, 8.3, 8.4, 8.5
     */
    public AnalysisResult analyzeRequirements(ParsedIssue parsedIssue) {
        Objects.requireNonNull(parsedIssue, "Parsed issue cannot be null");
        
        logger.debug("Analyzing requirements for issue: {}", parsedIssue);
        
        String body = parsedIssue.getBody();
        List<ParsedIssue.Section> sections = parsedIssue.getSections();
        
        // Extract all requirements elements
        String intent = extractIntent(body, sections);
        List<String> actors = identifyActors(body, sections);
        List<String> stakeholders = identifyStakeholders(actors);
        List<Requirement> preconditions = detectPreconditions(body, sections);
        List<Requirement> functionalRequirements = identifyFunctionalRequirements(body, sections);
        List<Requirement> errorFlows = detectErrorFlows(body, sections);
        List<Requirement> businessRules = extractBusinessRules(body, sections);
        List<DataRequirement> dataRequirements = identifyDataRequirements(body, sections);
        List<OpenQuestion> ambiguities = flagAmbiguities(body, sections, intent, actors);
        
        logger.info("Analysis complete: {} actors, {} requirements, {} ambiguities",
                   actors.size(), functionalRequirements.size(), ambiguities.size());
        
        return new AnalysisResult(
            intent,
            actors,
            stakeholders,
            preconditions,
            functionalRequirements,
            errorFlows,
            businessRules,
            dataRequirements,
            ambiguities
        );
    }

    /**
     * Extracts business intent from the issue body.
     * Looks for user story format and intent statements.
     * 
     * Requirements: 3.1, 7.3
     */
    private String extractIntent(String body, List<ParsedIssue.Section> sections) {
        logger.debug("Extracting business intent");
        
        // Check for explicit intent in sections
        for (ParsedIssue.Section section : sections) {
            String heading = section.getHeading().toLowerCase();
            if (heading.contains("description") || heading.contains("overview") || 
                heading.contains("intent") || heading.contains("purpose")) {
                String content = section.getContent();
                if (!content.isEmpty()) {
                    return extractIntentFromText(content);
                }
            }
        }
        
        // Fall back to extracting from full body
        return extractIntentFromText(body);
    }
    
    private String extractIntentFromText(String text) {
        Matcher matcher = INTENT_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        
        // If no explicit intent pattern, use first sentence
        String[] sentences = text.split("[.!?]");
        if (sentences.length > 0) {
            return sentences[0].trim();
        }
        
        return "Intent not clearly specified";
    }

    /**
     * Identifies actors from the issue content.
     * Looks for "As a [actor]" patterns and role mentions.
     * 
     * Requirements: 7.3
     */
    private List<String> identifyActors(String body, List<ParsedIssue.Section> sections) {
        logger.debug("Identifying actors");
        
        Set<String> actors = new LinkedHashSet<>();
        
        // Combine body and section content for actor extraction
        StringBuilder allText = new StringBuilder(body);
        for (ParsedIssue.Section section : sections) {
            allText.append(" ").append(section.getContent());
        }
        String fullText = allText.toString();
        
        // Extract from user story format
        Matcher matcher = ACTOR_PATTERN.matcher(fullText);
        while (matcher.find()) {
            String actor = matcher.group(1).trim();
            actors.add(normalizeActor(actor));
        }
        
        // Common actor roles
        String lowerText = fullText.toLowerCase();
        if (lowerText.contains("user")) actors.add("User");
        if (lowerText.contains("admin")) actors.add("Administrator");
        if (lowerText.contains("customer")) actors.add("Customer");
        if (lowerText.contains("developer")) actors.add("Developer");
        if (lowerText.contains("system")) actors.add("System");
        
        if (actors.isEmpty()) {
            actors.add("User"); // Default actor
        }
        
        return new ArrayList<>(actors);
    }
    
    private String normalizeActor(String actor) {
        // Capitalize first letter of each word
        return Arrays.stream(actor.split("\\s+"))
            .map(word -> word.substring(0, 1).toUpperCase() + word.substring(1).toLowerCase())
            .collect(Collectors.joining(" "));
    }

    /**
     * Identifies stakeholders based on actors.
     * Stakeholders are derived from actors and their relationships.
     * 
     * Requirements: 7.3
     */
    private List<String> identifyStakeholders(List<String> actors) {
        logger.debug("Identifying stakeholders from actors");
        
        Set<String> stakeholders = new LinkedHashSet<>(actors);
        
        // Add common stakeholders based on actors
        if (actors.stream().anyMatch(a -> a.toLowerCase().contains("customer"))) {
            stakeholders.add("Business Owner");
        }
        if (actors.stream().anyMatch(a -> a.toLowerCase().contains("admin"))) {
            stakeholders.add("System Administrator");
        }
        
        return new ArrayList<>(stakeholders);
    }

    /**
     * Detects preconditions and state dependencies.
     * Looks for state-related keywords and conditional statements.
     * 
     * Requirements: 3.1
     */
    private List<Requirement> detectPreconditions(String body, List<ParsedIssue.Section> sections) {
        logger.debug("Detecting preconditions");
        
        List<Requirement> preconditions = new ArrayList<>();
        
        // Look for precondition sections
        for (ParsedIssue.Section section : sections) {
            String heading = section.getHeading().toLowerCase();
            if (heading.contains("precondition") || heading.contains("prerequisite") ||
                heading.contains("assumption") || heading.contains("given")) {
                String content = section.getContent();
                preconditions.addAll(extractRequirementsFromText(content, EarsPattern.STATE_DRIVEN));
            }
        }
        
        // Extract from conditional statements in body
        String[] sentences = body.split("[.!?]");
        for (String sentence : sentences) {
            sentence = sentence.trim();
            if (sentence.isEmpty()) continue;
            
            // Check if sentence contains state-related patterns
            String lowerSentence = sentence.toLowerCase();
            if (lowerSentence.contains("in ") && lowerSentence.contains(" mode")) {
                // Pattern: "in maintenance mode", "in active mode", etc.
                preconditions.add(new Requirement(sentence, EarsPattern.STATE_DRIVEN, true));
            } else if (lowerSentence.matches(".*\\b(when|while|during)\\b.*\\b(state|status|mode)\\b.*")) {
                // Pattern: "when the system is in X state"
                preconditions.add(new Requirement(sentence, EarsPattern.STATE_DRIVEN, true));
            }
        }
        
        return preconditions;
    }
    
    private boolean containsStateKeyword(String text) {
        String lowerText = text.toLowerCase();
        return STATE_KEYWORDS.stream().anyMatch(lowerText::contains);
    }

    /**
     * Identifies functional requirements from the issue.
     * Looks for requirement sections and action statements.
     * 
     * Requirements: 3.1, 7.3
     */
    private List<Requirement> identifyFunctionalRequirements(String body, List<ParsedIssue.Section> sections) {
        logger.debug("Identifying functional requirements");
        
        List<Requirement> requirements = new ArrayList<>();
        
        // Look for requirements sections
        for (ParsedIssue.Section section : sections) {
            String heading = section.getHeading().toLowerCase();
            if (heading.contains("requirement") || heading.contains("acceptance") ||
                heading.contains("criteria") || heading.contains("feature")) {
                String content = section.getContent();
                requirements.addAll(extractRequirementsFromText(content, EarsPattern.UBIQUITOUS));
            }
        }
        
        return requirements;
    }
    
    private List<Requirement> extractRequirementsFromText(String text, EarsPattern defaultPattern) {
        List<Requirement> requirements = new ArrayList<>();
        
        // Split by bullet points or numbered lists
        String[] lines = text.split("\\n");
        for (String line : lines) {
            line = line.trim();
            // Skip empty lines and very short lines
            if (line.length() < 10) continue;
            
            // Remove bullet points and numbers
            line = line.replaceFirst("^[-*•]\\s*", "");
            line = line.replaceFirst("^\\d+\\.\\s*", "");
            
            if (!line.isEmpty()) {
                // For explicit patterns (UNWANTED, STATE_DRIVEN), use them unless text clearly indicates otherwise
                EarsPattern pattern;
                if (defaultPattern == EarsPattern.UNWANTED || defaultPattern == EarsPattern.STATE_DRIVEN) {
                    pattern = defaultPattern;
                } else {
                    pattern = determinePattern(line);
                    if (pattern == null) {
                        pattern = defaultPattern;
                    }
                }
                
                boolean verifiable = isVerifiable(line);
                requirements.add(new Requirement(line, pattern, verifiable));
            }
        }
        
        return requirements;
    }
    
    private EarsPattern determinePattern(String text) {
        String lower = text.toLowerCase();
        
        if (lower.contains("when ") || lower.contains("if ")) {
            return lower.contains("error") || lower.contains("fail") ? 
                   EarsPattern.UNWANTED : EarsPattern.EVENT_DRIVEN;
        }
        if (lower.contains("while ") || lower.contains("during ")) {
            return EarsPattern.STATE_DRIVEN;
        }
        
        return EarsPattern.UBIQUITOUS;
    }
    
    private boolean isVerifiable(String text) {
        // A requirement is verifiable if it doesn't contain vague terms
        String lowerText = text.toLowerCase();
        return VAGUE_TERMS.stream().noneMatch(lowerText::contains);
    }

    /**
     * Detects error flows and exception handling requirements.
     * Looks for error-related keywords and failure scenarios.
     * 
     * Requirements: 3.1
     */
    private List<Requirement> detectErrorFlows(String body, List<ParsedIssue.Section> sections) {
        logger.debug("Detecting error flows");
        
        List<Requirement> errorFlows = new ArrayList<>();
        
        // Look for error/exception sections
        for (ParsedIssue.Section section : sections) {
            String heading = section.getHeading().toLowerCase();
            if (heading.contains("error") || heading.contains("exception") ||
                heading.contains("failure") || heading.contains("alternate")) {
                String content = section.getContent();
                errorFlows.addAll(extractRequirementsFromText(content, EarsPattern.UNWANTED));
            }
        }
        
        // Extract error-related statements from body
        String[] sentences = body.split("[.!?]");
        for (String sentence : sentences) {
            if (containsErrorKeyword(sentence)) {
                errorFlows.add(new Requirement(sentence.trim(), EarsPattern.UNWANTED, true));
            }
        }
        
        return errorFlows;
    }
    
    private boolean containsErrorKeyword(String text) {
        String lowerText = text.toLowerCase();
        return ERROR_KEYWORDS.stream().anyMatch(lowerText::contains);
    }

    /**
     * Extracts business rules from the issue.
     * Business rules are always-true constraints.
     * 
     * Requirements: 3.1
     */
    private List<Requirement> extractBusinessRules(String body, List<ParsedIssue.Section> sections) {
        logger.debug("Extracting business rules");
        
        List<Requirement> businessRules = new ArrayList<>();
        
        // Look for business rules sections
        for (ParsedIssue.Section section : sections) {
            String heading = section.getHeading().toLowerCase();
            if (heading.contains("rule") || heading.contains("constraint") ||
                heading.contains("policy") || heading.contains("validation")) {
                String content = section.getContent();
                businessRules.addAll(extractRequirementsFromText(content, EarsPattern.UBIQUITOUS));
            }
        }
        
        return businessRules;
    }

    /**
     * Identifies data requirements from the issue.
     * Looks for field names, data structures, and data-related sections.
     * 
     * Requirements: 3.1
     */
    private List<DataRequirement> identifyDataRequirements(String body, List<ParsedIssue.Section> sections) {
        logger.debug("Identifying data requirements");
        
        List<DataRequirement> dataRequirements = new ArrayList<>();
        
        // Look for data sections
        for (ParsedIssue.Section section : sections) {
            String heading = section.getHeading().toLowerCase();
            if (heading.contains("data") || heading.contains("field") ||
                heading.contains("model") || heading.contains("schema")) {
                String content = section.getContent();
                dataRequirements.addAll(extractDataFieldsFromText(content));
            }
        }
        
        // Extract field mentions from body
        Matcher matcher = DATA_FIELD_PATTERN.matcher(body);
        while (matcher.find()) {
            String fieldName = matcher.group(1);
            dataRequirements.add(new DataRequirement(
                fieldName,
                "Field mentioned in requirements",
                false // Default to not required
            ));
        }
        
        return dataRequirements;
    }
    
    private List<DataRequirement> extractDataFieldsFromText(String text) {
        List<DataRequirement> fields = new ArrayList<>();
        
        String[] lines = text.split("\\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            
            // Look for field definitions
            if (line.contains(":")) {
                String[] parts = line.split(":", 2);
                if (parts.length == 2) {
                    String fieldName = parts[0].trim().replaceFirst("^[-*•]\\s*", "");
                    String description = parts[1].trim();
                    boolean required = description.toLowerCase().contains("required") ||
                                     description.toLowerCase().contains("mandatory");
                    fields.add(new DataRequirement(fieldName, description, required));
                }
            }
        }
        
        return fields;
    }

    /**
     * Flags ambiguities and uncertainties in the requirements.
     * Looks for vague terms, missing information, and unclear specifications.
     * 
     * Requirements: 7.1, 8.1, 8.2, 8.3, 8.4, 8.5
     */
    private List<OpenQuestion> flagAmbiguities(String body, List<ParsedIssue.Section> sections,
                                               String intent, List<String> actors) {
        logger.debug("Flagging ambiguities");
        
        List<OpenQuestion> ambiguities = new ArrayList<>();
        
        // Check for vague terms
        for (String vagueTerm : VAGUE_TERMS) {
            if (body.toLowerCase().contains(vagueTerm)) {
                ambiguities.add(new OpenQuestion(
                    "What specific criteria define '" + vagueTerm + "'?",
                    "Vague terms make requirements unverifiable and lead to implementation ambiguity",
                    "High - affects testability and acceptance criteria"
                ));
                break; // Only flag once for vague terms
            }
        }
        
        // Check for unclear intent
        if (intent.equals("Intent not clearly specified") || intent.length() < 10) {
            ambiguities.add(new OpenQuestion(
                "What is the primary business intent of this feature?",
                "Clear intent is essential for understanding the purpose and value of the feature",
                "Critical - affects entire implementation direction"
            ));
        }
        
        // Check for missing actors
        if (actors.isEmpty() || (actors.size() == 1 && actors.get(0).equals("User"))) {
            ambiguities.add(new OpenQuestion(
                "Who are the specific actors/users for this feature?",
                "Identifying actors helps define permissions, workflows, and user experience",
                "High - affects security and UX design"
            ));
        }
        
        // Check for missing data specifications
        if (!body.toLowerCase().contains("data") && !body.toLowerCase().contains("field")) {
            ambiguities.add(new OpenQuestion(
                "What data fields and structures are required?",
                "Data requirements are essential for database design and API contracts",
                "High - affects data model and persistence layer"
            ));
        }
        
        // Check for missing error handling
        if (!containsErrorKeyword(body)) {
            ambiguities.add(new OpenQuestion(
                "How should errors and edge cases be handled?",
                "Error handling is critical for system reliability and user experience",
                "Medium - affects robustness and error recovery"
            ));
        }
        
        return ambiguities;
    }
}

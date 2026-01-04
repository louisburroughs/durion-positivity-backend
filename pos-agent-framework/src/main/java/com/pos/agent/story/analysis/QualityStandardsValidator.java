package com.pos.agent.story.analysis;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Quality Standards Validator for ISO/IEC/IEEE 29148 compliance.
 * 
 * Validates requirements against quality standards including:
 * - Vague term detection and replacement
 * - Acceptance criteria presence verification
 * - Requirement completeness checks (actor, trigger, outcome)
 * - Terminology consistency checks
 * - Verifiability flagging for untestable requirements
 * 
 * Requirements: 7.1, 7.2, 7.3, 7.4, 7.6
 */
public class QualityStandardsValidator {
    
    // Vague terms that should be flagged or replaced (Requirement 7.1)
    private static final Set<String> VAGUE_TERMS = Set.of(
        "quickly", "slowly", "fast", "slow",
        "adequate", "inadequate", "sufficient", "insufficient",
        "appropriate", "inappropriate", "suitable", "unsuitable",
        "reasonable", "unreasonable", "acceptable", "unacceptable",
        "efficient", "inefficient", "effective", "ineffective",
        "user-friendly", "easy", "simple", "complex",
        "robust", "flexible", "scalable", "maintainable",
        "high", "low", "large", "small",
        "many", "few", "several", "various",
        "etc", "and so on", "and so forth",
        "as needed", "as appropriate", "as required",
        "timely", "promptly", "immediately", "soon"
    );
    
    // Pattern to detect EARS-style requirements
    private static final Pattern EARS_PATTERN = Pattern.compile(
        "(?i)(THE system SHALL|WHILE .+ THE system SHALL|WHEN .+ THE system SHALL|IF .+ THEN THE system SHALL)"
    );
    
    // Pattern to detect Gherkin scenarios
    private static final Pattern GHERKIN_PATTERN = Pattern.compile(
        "(?i)(Given|When|Then|And)\\s+"
    );
    
    // Pattern to detect actor mentions
    private static final Pattern ACTOR_PATTERN = Pattern.compile(
        "(?i)\\b(user|customer|admin|administrator|system|service|developer|tester|operator|manager)\\b"
    );
    
    // Pattern to detect trigger/action words
    private static final Pattern TRIGGER_PATTERN = Pattern.compile(
        "(?i)\\b(when|if|after|before|upon|on|during|while|submits?|creates?|updates?|deletes?|requests?|receives?|sends?|processes?)\\b"
    );
    
    // Pattern to detect outcome/result words
    private static final Pattern OUTCOME_PATTERN = Pattern.compile(
        "(?i)\\b(shall|must|will|should|then|returns?|displays?|shows?|stores?|saves?|validates?|rejects?|accepts?|notifies?|sends?|creates?|updates?)\\b"
    );
    
    /**
     * Validates requirements text against ISO/IEC/IEEE 29148 quality standards.
     * 
     * @param requirementsText The requirements text to validate
     * @return ValidationResult containing all quality issues found
     */
    public ValidationResult validateRequirements(String requirementsText) {
        if (requirementsText == null || requirementsText.trim().isEmpty()) {
            return new ValidationResult(
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
            );
        }
        
        List<String> vagueTerms = detectVagueTerms(requirementsText);
        List<String> missingAcceptanceCriteria = checkAcceptanceCriteriaPresence(requirementsText);
        List<String> incompleteRequirements = checkRequirementCompleteness(requirementsText);
        List<String> unverifiableRequirements = flagUnverifiableRequirements(requirementsText);
        List<String> terminologyInconsistencies = checkTerminologyConsistency(requirementsText);
        
        return new ValidationResult(
            vagueTerms,
            missingAcceptanceCriteria,
            incompleteRequirements,
            unverifiableRequirements,
            terminologyInconsistencies
        );
    }
    
    /**
     * Detects vague terms in requirements text.
     * Requirement 7.1: Replace or flag vague terms to ensure unambiguous language
     * 
     * @param text The text to analyze
     * @return List of vague terms found with context
     */
    public List<String> detectVagueTerms(String text) {
        List<String> found = new ArrayList<>();
        String lowerText = text.toLowerCase();
        
        for (String vagueTerm : VAGUE_TERMS) {
            if (lowerText.contains(vagueTerm.toLowerCase())) {
                // Find context around the vague term
                int index = lowerText.indexOf(vagueTerm.toLowerCase());
                int start = Math.max(0, index - 30);
                int end = Math.min(text.length(), index + vagueTerm.length() + 30);
                String context = text.substring(start, end).trim();
                found.add("Vague term '" + vagueTerm + "' in: \"..." + context + "...\"");
            }
        }
        
        return found;
    }
    
    /**
     * Checks if requirements have corresponding acceptance criteria.
     * Requirement 7.2: Ensure every rule has acceptance criteria to ensure verifiability
     * 
     * @param text The requirements text to check
     * @return List of requirements missing acceptance criteria
     */
    public List<String> checkAcceptanceCriteriaPresence(String text) {
        List<String> missing = new ArrayList<>();
        
        // Split into sections
        String[] sections = text.split("(?i)##\\s+");
        
        boolean hasRequirementsSection = false;
        boolean hasAcceptanceCriteriaSection = false;
        
        for (String section : sections) {
            String sectionLower = section.toLowerCase();
            if (sectionLower.contains("requirement") || 
                sectionLower.contains("functional") ||
                sectionLower.contains("business rule")) {
                hasRequirementsSection = true;
            }
            if (sectionLower.contains("acceptance criteria") ||
                sectionLower.contains("acceptance criterion")) {
                hasAcceptanceCriteriaSection = true;
            }
        }
        
        if (hasRequirementsSection && !hasAcceptanceCriteriaSection) {
            missing.add("Requirements section found but no Acceptance Criteria section present");
        }
        
        // Check for EARS statements without corresponding Gherkin scenarios
        Matcher earsMatcher = EARS_PATTERN.matcher(text);
        int earsCount = 0;
        while (earsMatcher.find()) {
            earsCount++;
        }
        
        Matcher gherkinMatcher = GHERKIN_PATTERN.matcher(text);
        int gherkinCount = 0;
        while (gherkinMatcher.find()) {
            gherkinCount++;
        }
        
        if (earsCount > 0 && gherkinCount == 0) {
            missing.add("EARS requirements found (" + earsCount + ") but no Gherkin acceptance criteria present");
        }
        
        return missing;
    }
    
    /**
     * Checks if requirements are complete (actor, trigger, outcome).
     * Requirement 7.3: Ensure actor, trigger, and outcome are present to ensure completeness
     * 
     * @param text The requirements text to check
     * @return List of incomplete requirements
     */
    public List<String> checkRequirementCompleteness(String text) {
        List<String> incomplete = new ArrayList<>();
        
        // Split into individual requirements (lines starting with SHALL or Gherkin keywords)
        String[] lines = text.split("\n");
        
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            
            // Check EARS-style requirements first (they take precedence)
            if (EARS_PATTERN.matcher(trimmed).find()) {
                boolean hasActor = ACTOR_PATTERN.matcher(trimmed).find();
                boolean hasTrigger = TRIGGER_PATTERN.matcher(trimmed).find();
                // For EARS, "SHALL" is the outcome indicator, so if EARS pattern matches, outcome is present
                boolean hasOutcome = true; // EARS pattern already includes SHALL
                
                if (!hasActor || !hasTrigger) {
                    List<String> missing = new ArrayList<>();
                    if (!hasActor) missing.add("actor");
                    if (!hasTrigger) missing.add("trigger");
                    
                    String preview = trimmed.length() > 80 ? trimmed.substring(0, 80) + "..." : trimmed;
                    incomplete.add("Incomplete requirement (missing " + String.join(", ", missing) + "): \"" + preview + "\"");
                }
                // Skip Gherkin check for EARS requirements
                continue;
            }
            
            // Check Gherkin-style requirements - only for lines that start with Gherkin keywords
            // and are NOT part of an EARS pattern
            if ((trimmed.toLowerCase().startsWith("given") || 
                 trimmed.toLowerCase().startsWith("scenario:")) &&
                !EARS_PATTERN.matcher(trimmed).find()) {
                // Gherkin scenarios should have Given (actor/state), When (trigger), Then (outcome)
                String scenarioBlock = extractScenarioBlock(text, trimmed);
                boolean hasGiven = scenarioBlock.toLowerCase().contains("given");
                boolean hasWhen = scenarioBlock.toLowerCase().contains("when");
                boolean hasThen = scenarioBlock.toLowerCase().contains("then");
                
                if (!hasGiven || !hasWhen || !hasThen) {
                    List<String> missing = new ArrayList<>();
                    if (!hasGiven) missing.add("Given");
                    if (!hasWhen) missing.add("When");
                    if (!hasThen) missing.add("Then");
                    
                    String preview = trimmed.length() > 80 ? trimmed.substring(0, 80) + "..." : trimmed;
                    incomplete.add("Incomplete Gherkin scenario (missing " + String.join(", ", missing) + "): \"" + preview + "\"");
                }
            }
        }
        
        return incomplete;
    }
    
    /**
     * Flags requirements that cannot be verified/tested.
     * Requirement 7.6: If a requirement cannot be made verifiable, flag it as an open question
     * 
     * @param text The requirements text to check
     * @return List of unverifiable requirements
     */
    public List<String> flagUnverifiableRequirements(String text) {
        List<String> unverifiable = new ArrayList<>();
        
        // Patterns that indicate unverifiable requirements
        Pattern unverifiablePattern = Pattern.compile(
            "(?i)\\b(user-friendly|intuitive|easy to use|simple|elegant|beautiful|" +
            "performant|efficient|robust|flexible|maintainable|scalable|" +
            "as needed|as appropriate|as required|when necessary)\\b"
        );
        
        String[] lines = text.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            
            // Check if line contains a requirement
            if (EARS_PATTERN.matcher(trimmed).find() || GHERKIN_PATTERN.matcher(trimmed).find()) {
                Matcher matcher = unverifiablePattern.matcher(trimmed);
                if (matcher.find()) {
                    String preview = trimmed.length() > 80 ? trimmed.substring(0, 80) + "..." : trimmed;
                    unverifiable.add("Unverifiable requirement (contains '" + matcher.group() + "'): \"" + preview + "\"");
                }
            }
        }
        
        return unverifiable;
    }
    
    /**
     * Checks for terminology consistency across requirements.
     * Requirement 7.4: Align states and terminology to ensure consistency
     * 
     * @param text The requirements text to check
     * @return List of terminology inconsistencies
     */
    public List<String> checkTerminologyConsistency(String text) {
        List<String> inconsistencies = new ArrayList<>();
        
        // Track term variations
        Map<String, Set<String>> termVariations = new HashMap<>();
        
        // Common term groups that should be consistent
        Map<String, Set<String>> termGroups = Map.of(
            "user", Set.of("user", "customer", "client", "end-user", "end user"),
            "create", Set.of("add", "insert", "new"),  // Removed "create" from variations
            "update", Set.of("modify", "change", "edit"),  // Removed "update" from variations
            "delete", Set.of("remove", "destroy", "erase"),  // Removed "delete" from variations
            "retrieve", Set.of("get", "fetch", "read", "query"),  // Removed "retrieve" from variations
            "validate", Set.of("verify", "check", "confirm")  // Removed "validate" from variations
        );
        
        String lowerText = text.toLowerCase();
        
        for (Map.Entry<String, Set<String>> entry : termGroups.entrySet()) {
            String canonical = entry.getKey();
            Set<String> variations = entry.getValue();
            Set<String> foundVariations = new HashSet<>();
            
            // Check if canonical term is present
            if (lowerText.contains(canonical)) {
                foundVariations.add(canonical);
            }
            
            // Check for variations
            for (String variation : variations) {
                if (lowerText.contains(variation)) {
                    foundVariations.add(variation);
                }
            }
            
            // Only flag if we found the canonical term AND variations
            if (foundVariations.contains(canonical) && foundVariations.size() > 1) {
                inconsistencies.add("Inconsistent terminology for '" + canonical + "': found " + foundVariations);
            }
        }
        
        return inconsistencies;
    }
    
    /**
     * Suggests replacements for vague terms.
     * 
     * @param vagueTerm The vague term to replace
     * @return Suggested replacement or guidance
     */
    public String suggestReplacement(String vagueTerm) {
        return switch (vagueTerm.toLowerCase()) {
            case "quickly", "fast" -> "within X seconds/milliseconds";
            case "slowly", "slow" -> "taking more than X seconds";
            case "adequate", "sufficient" -> "at least X units/items";
            case "appropriate", "suitable" -> "meeting criteria: [specify criteria]";
            case "reasonable", "acceptable" -> "within range X to Y";
            case "efficient", "performant" -> "using less than X resources";
            case "user-friendly", "easy", "simple" -> "requiring fewer than X steps";
            case "robust", "flexible" -> "handling X error conditions";
            case "scalable" -> "supporting up to X concurrent users/requests";
            case "high", "large" -> "greater than X";
            case "low", "small" -> "less than X";
            case "many", "several" -> "at least X items";
            case "timely", "promptly" -> "within X time units";
            default -> "specify measurable criteria";
        };
    }
    
    /**
     * Extracts a Gherkin scenario block from text starting at a given line.
     */
    private String extractScenarioBlock(String fullText, String startLine) {
        int startIndex = fullText.indexOf(startLine);
        if (startIndex == -1) return startLine;
        
        // Find the end of the scenario (next blank line or next scenario)
        int endIndex = fullText.indexOf("\n\n", startIndex);
        if (endIndex == -1) endIndex = fullText.length();
        
        return fullText.substring(startIndex, endIndex);
    }
}

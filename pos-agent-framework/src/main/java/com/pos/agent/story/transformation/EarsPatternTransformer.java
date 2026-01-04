package com.pos.agent.story.transformation;

import com.pos.agent.story.models.Requirement;
import com.pos.agent.story.models.Requirement.EarsPattern;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Transforms requirements into EARS (Easy Approach to Requirements Syntax) format.
 * Applies appropriate EARS patterns based on requirement type and ensures consistent phrasing.
 * 
 * EARS Patterns:
 * - UBIQUITOUS: THE system SHALL (always-true behavior)
 * - STATE_DRIVEN: WHILE ... THE system SHALL (preconditions, lifecycle)
 * - EVENT_DRIVEN: WHEN ... THE system SHALL (triggers, submissions)
 * - UNWANTED: IF ... THEN THE system SHALL (failures, rejections)
 * 
 * Requirements: 6.1, 6.2, 6.3, 6.4, 6.5
 */
public class EarsPatternTransformer {

    private static final String SYSTEM_SHALL = "the system SHALL";
    
    // Patterns for detecting existing EARS-like structures
    private static final Pattern WHEN_PATTERN = Pattern.compile("(?i)^\\s*when\\s+(.+?)\\s+then\\s+(.+)$", Pattern.DOTALL);
    private static final Pattern WHILE_PATTERN = Pattern.compile("(?i)^\\s*while\\s+(.+?)\\s+then\\s+(.+)$", Pattern.DOTALL);
    private static final Pattern IF_PATTERN = Pattern.compile("(?i)^\\s*if\\s+(.+?)\\s+then\\s+(.+)$", Pattern.DOTALL);
    
    /**
     * Transforms a requirement into EARS format based on its pattern type.
     * 
     * @param requirement The requirement to transform
     * @return The transformed requirement text in EARS format
     * Requirements: 6.1, 6.2, 6.3, 6.4, 6.5
     */
    public String transformRequirement(Requirement requirement) {
        if (requirement == null) {
            throw new IllegalArgumentException("Requirement cannot be null");
        }
        
        String text = requirement.getText().trim();
        EarsPattern pattern = requirement.getPattern();
        
        return switch (pattern) {
            case UBIQUITOUS -> applyUbiquitousPattern(text);
            case STATE_DRIVEN -> applyStateDrivenPattern(text);
            case EVENT_DRIVEN -> applyEventDrivenPattern(text);
            case UNWANTED -> applyUnwantedPattern(text);
        };
    }
    
    /**
     * Transforms a list of requirements into EARS format.
     * 
     * @param requirements The requirements to transform
     * @return List of transformed requirement texts
     * Requirements: 6.1, 6.2, 6.3, 6.4, 6.5
     */
    public List<String> transformRequirements(List<Requirement> requirements) {
        if (requirements == null) {
            throw new IllegalArgumentException("Requirements list cannot be null");
        }
        
        List<String> transformed = new ArrayList<>();
        for (Requirement req : requirements) {
            transformed.add(transformRequirement(req));
        }
        return transformed;
    }
    
    /**
     * Applies ubiquitous pattern: THE system SHALL (always-true behavior).
     * Used for requirements that are always active without preconditions.
     * 
     * @param text The requirement text
     * @return The text formatted with ubiquitous pattern
     * Requirements: 6.1, 6.5
     */
    private String applyUbiquitousPattern(String text) {
        // If already starts with "THE system SHALL", return as-is
        if (text.matches("(?i)^THE\\s+system\\s+SHALL\\s+.+")) {
            return normalizeSystemShall(text);
        }
        
        // Remove common prefixes that indicate ubiquitous behavior
        text = text.replaceFirst("(?i)^(the\\s+system\\s+)?(must|should|will|shall)\\s+", "");
        text = text.replaceFirst("(?i)^(always\\s+)?", "");
        
        // Ensure first letter is lowercase for consistency
        if (!text.isEmpty()) {
            text = Character.toLowerCase(text.charAt(0)) + text.substring(1);
        }
        
        return "THE " + SYSTEM_SHALL + " " + text;
    }
    
    /**
     * Applies state-driven pattern: WHILE ... THE system SHALL (preconditions, lifecycle).
     * Used for requirements that depend on a specific system state or condition.
     * 
     * @param text The requirement text
     * @return The text formatted with state-driven pattern
     * Requirements: 6.2, 6.5
     */
    private String applyStateDrivenPattern(String text) {
        // Check if already in WHILE format
        Matcher whileMatcher = WHILE_PATTERN.matcher(text);
        if (whileMatcher.matches()) {
            String condition = whileMatcher.group(1).trim();
            String action = whileMatcher.group(2).trim();
            return formatStateDriven(condition, action);
        }
        
        // Try to extract condition from various formats
        String condition = null;
        String action = text;
        
        // Pattern: "in state X, do Y" or "when in state X, do Y"
        Pattern statePattern = Pattern.compile("(?i)^(?:when\\s+)?(?:in|during)\\s+(.+?)\\s+state[,:]?\\s+(.+)$", Pattern.DOTALL);
        Matcher stateMatcher = statePattern.matcher(text);
        if (stateMatcher.matches()) {
            condition = stateMatcher.group(1).trim() + " state";
            action = stateMatcher.group(2).trim();
        }
        
        // Pattern: "while X, do Y"
        if (condition == null) {
            Pattern whilePattern = Pattern.compile("(?i)^while\\s+(.+?)[,:]\\s+(.+)$", Pattern.DOTALL);
            Matcher matcher = whilePattern.matcher(text);
            if (matcher.matches()) {
                condition = matcher.group(1).trim();
                action = matcher.group(2).trim();
            }
        }
        
        // If no condition found, create a generic one
        if (condition == null) {
            condition = "in the appropriate state";
            action = text;
        }
        
        return formatStateDriven(condition, action);
    }
    
    /**
     * Applies event-driven pattern: WHEN ... THE system SHALL (triggers, submissions).
     * Used for requirements triggered by specific events or user actions.
     * 
     * @param text The requirement text
     * @return The text formatted with event-driven pattern
     * Requirements: 6.3, 6.5
     */
    private String applyEventDrivenPattern(String text) {
        // Check if already in WHEN format
        Matcher whenMatcher = WHEN_PATTERN.matcher(text);
        if (whenMatcher.matches()) {
            String trigger = whenMatcher.group(1).trim();
            String action = whenMatcher.group(2).trim();
            return formatEventDriven(trigger, action);
        }
        
        // Try to extract trigger from various formats
        String trigger = null;
        String action = text;
        
        // Pattern: "when X, do Y" or "when X then Y"
        Pattern whenPattern = Pattern.compile("(?i)^when\\s+(.+?)[,:]\\s+(.+)$", Pattern.DOTALL);
        Matcher matcher = whenPattern.matcher(text);
        if (matcher.matches()) {
            trigger = matcher.group(1).trim();
            action = matcher.group(2).trim();
        }
        
        // Pattern: "on X, do Y" or "upon X, do Y"
        if (trigger == null) {
            Pattern onPattern = Pattern.compile("(?i)^(?:on|upon)\\s+(.+?)[,:]\\s+(.+)$", Pattern.DOTALL);
            Matcher onMatcher = onPattern.matcher(text);
            if (onMatcher.matches()) {
                trigger = onMatcher.group(1).trim();
                action = onMatcher.group(2).trim();
            }
        }
        
        // Pattern: "after X, do Y"
        if (trigger == null) {
            Pattern afterPattern = Pattern.compile("(?i)^after\\s+(.+?)[,:]\\s+(.+)$", Pattern.DOTALL);
            Matcher afterMatcher = afterPattern.matcher(text);
            if (afterMatcher.matches()) {
                trigger = afterMatcher.group(1).trim();
                action = afterMatcher.group(2).trim();
            }
        }
        
        // If no trigger found, create a generic one
        if (trigger == null) {
            trigger = "the event occurs";
            action = text;
        }
        
        return formatEventDriven(trigger, action);
    }
    
    /**
     * Applies unwanted behavior pattern: IF ... THEN THE system SHALL (failures, rejections).
     * Used for requirements that handle error conditions or unwanted scenarios.
     * 
     * @param text The requirement text
     * @return The text formatted with unwanted pattern
     * Requirements: 6.4, 6.5
     */
    private String applyUnwantedPattern(String text) {
        // Check if already in IF...THEN format
        Matcher ifMatcher = IF_PATTERN.matcher(text);
        if (ifMatcher.matches()) {
            String condition = ifMatcher.group(1).trim();
            String action = ifMatcher.group(2).trim();
            return formatUnwanted(condition, action);
        }
        
        // Try to extract condition from various formats
        String condition = null;
        String action = text;
        
        // Pattern: "if X, do Y" or "if X then Y"
        Pattern ifPattern = Pattern.compile("(?i)^if\\s+(.+?)[,:]\\s+(.+)$", Pattern.DOTALL);
        Matcher matcher = ifPattern.matcher(text);
        if (matcher.matches()) {
            condition = matcher.group(1).trim();
            action = matcher.group(2).trim();
        }
        
        // Pattern: "in case of X, do Y"
        if (condition == null) {
            Pattern casePattern = Pattern.compile("(?i)^in\\s+case\\s+of\\s+(.+?)[,:]\\s+(.+)$", Pattern.DOTALL);
            Matcher caseMatcher = casePattern.matcher(text);
            if (caseMatcher.matches()) {
                condition = caseMatcher.group(1).trim();
                action = caseMatcher.group(2).trim();
            }
        }
        
        // If no condition found, create a generic one
        if (condition == null) {
            condition = "an error occurs";
            action = text;
        }
        
        return formatUnwanted(condition, action);
    }
    
    /**
     * Formats a state-driven requirement with proper EARS syntax.
     */
    private String formatStateDriven(String condition, String action) {
        action = normalizeAction(action);
        return "WHILE " + condition + ", THE " + SYSTEM_SHALL + " " + action;
    }
    
    /**
     * Formats an event-driven requirement with proper EARS syntax.
     */
    private String formatEventDriven(String trigger, String action) {
        action = normalizeAction(action);
        return "WHEN " + trigger + ", THE " + SYSTEM_SHALL + " " + action;
    }
    
    /**
     * Formats an unwanted behavior requirement with proper EARS syntax.
     */
    private String formatUnwanted(String condition, String action) {
        action = normalizeAction(action);
        return "IF " + condition + ", THEN THE " + SYSTEM_SHALL + " " + action;
    }
    
    /**
     * Normalizes action text by removing common prefixes and ensuring lowercase start.
     */
    private String normalizeAction(String action) {
        // Remove "the system shall/must/should/will"
        action = action.replaceFirst("(?i)^(the\\s+)?system\\s+(shall|must|should|will)\\s+", "");
        
        // Remove "then" prefix
        action = action.replaceFirst("(?i)^then\\s+", "");
        
        // Ensure first letter is lowercase
        if (!action.isEmpty()) {
            action = Character.toLowerCase(action.charAt(0)) + action.substring(1);
        }
        
        return action;
    }
    
    /**
     * Normalizes "the system shall" to consistent casing.
     */
    private String normalizeSystemShall(String text) {
        return text.replaceFirst("(?i)THE\\s+system\\s+SHALL", "THE " + SYSTEM_SHALL);
    }
}

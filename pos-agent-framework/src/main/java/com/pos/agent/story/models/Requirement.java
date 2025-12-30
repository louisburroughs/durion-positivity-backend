package com.pos.agent.story.models;

import java.util.Objects;

/**
 * Represents a single requirement with its pattern type and verifiability status.
 * Used in the analysis and transformation stages.
 * 
 * Requirements: 6.1, 6.2, 6.3, 6.4, 7.2, 7.3
 */
public class Requirement {
    private final String text;
    private final EarsPattern pattern;
    private final boolean isVerifiable;

    public Requirement(String text, EarsPattern pattern, boolean isVerifiable) {
        this.text = Objects.requireNonNull(text, "Requirement text cannot be null");
        this.pattern = Objects.requireNonNull(pattern, "EARS pattern cannot be null");
        this.isVerifiable = isVerifiable;
    }

    public String getText() {
        return text;
    }

    public EarsPattern getPattern() {
        return pattern;
    }

    public boolean isVerifiable() {
        return isVerifiable;
    }

    /**
     * EARS (Easy Approach to Requirements Syntax) patterns.
     */
    public enum EarsPattern {
        UBIQUITOUS,      // THE system SHALL (always-true behavior)
        STATE_DRIVEN,    // WHILE ... THE system SHALL (preconditions, lifecycle)
        EVENT_DRIVEN,    // WHEN ... THE system SHALL (triggers, submissions)
        UNWANTED         // IF ... THEN THE system SHALL (failures, rejections)
    }

    @Override
    public String toString() {
        return String.format("Requirement{pattern=%s, verifiable=%s, text='%s'}", 
                           pattern, isVerifiable, text);
    }
}

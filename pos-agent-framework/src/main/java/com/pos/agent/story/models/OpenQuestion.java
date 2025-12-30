package com.pos.agent.story.models;

import java.util.Objects;

/**
 * Represents an ambiguity or uncertainty that requires human clarification.
 * Contains the question text and impact description.
 * 
 * Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7, 8.8
 */
public class OpenQuestion {
    private final String question;
    private final String whyItMatters;
    private final String impact;

    public OpenQuestion(String question, String whyItMatters, String impact) {
        this.question = Objects.requireNonNull(question, "Question cannot be null");
        this.whyItMatters = Objects.requireNonNull(whyItMatters, "Why it matters cannot be null");
        this.impact = Objects.requireNonNull(impact, "Impact cannot be null");
    }

    public String getQuestion() {
        return question;
    }

    public String getWhyItMatters() {
        return whyItMatters;
    }

    public String getImpact() {
        return impact;
    }

    @Override
    public String toString() {
        return String.format("OpenQuestion{question='%s', impact='%s'}", 
                           question, impact);
    }
}

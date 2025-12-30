package com.pos.agent.story.models;

import java.util.List;
import java.util.Objects;

/**
 * Represents a Gherkin scenario with Given/When/Then structure.
 * Used for functional requirements and acceptance criteria.
 * 
 * Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6
 */
public class GherkinScenario {
    private final String scenario;
    private final List<String> given;
    private final List<String> when;
    private final List<String> then;

    public GherkinScenario(String scenario, List<String> given, List<String> when, List<String> then) {
        this.scenario = Objects.requireNonNull(scenario, "Scenario name cannot be null");
        this.given = Objects.requireNonNull(given, "Given clauses cannot be null");
        this.when = Objects.requireNonNull(when, "When clauses cannot be null");
        this.then = Objects.requireNonNull(then, "Then clauses cannot be null");
    }

    public String getScenario() {
        return scenario;
    }

    public List<String> getGiven() {
        return given;
    }

    public List<String> getWhen() {
        return when;
    }

    public List<String> getThen() {
        return then;
    }

    @Override
    public String toString() {
        return String.format("GherkinScenario{scenario='%s', given=%d, when=%d, then=%d}", 
                           scenario, given.size(), when.size(), then.size());
    }
}

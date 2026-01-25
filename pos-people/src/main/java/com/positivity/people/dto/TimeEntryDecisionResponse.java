package com.positivity.people.dto;

import java.util.List;

public class TimeEntryDecisionResponse {
    private List<TimeEntryDecisionResult> results;

    public TimeEntryDecisionResponse() {
    }

    public TimeEntryDecisionResponse(List<TimeEntryDecisionResult> results) {
        this.results = results;
    }

    public List<TimeEntryDecisionResult> getResults() {
        return results;
    }

    public void setResults(List<TimeEntryDecisionResult> results) {
        this.results = results;
    }
}

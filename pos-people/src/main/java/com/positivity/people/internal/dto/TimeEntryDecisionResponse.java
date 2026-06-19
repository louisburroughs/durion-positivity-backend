package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Batch response containing per-entry decision outcomes")
public class TimeEntryDecisionResponse {

    @Schema(description = "Per-entry decision results", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<TimeEntryDecisionResult> results;

    public TimeEntryDecisionResponse() {}

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

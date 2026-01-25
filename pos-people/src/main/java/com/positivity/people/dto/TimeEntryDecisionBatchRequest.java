package com.positivity.people.dto;

import java.util.List;

public class TimeEntryDecisionBatchRequest {
    private List<Decision> decisions;

    public static class Decision {
        private String timeEntryId;
        private String rejectionReason;

        public Decision() {
        }

        public Decision(String timeEntryId) {
            this.timeEntryId = timeEntryId;
        }

        public String getTimeEntryId() {
            return timeEntryId;
        }

        public void setTimeEntryId(String timeEntryId) {
            this.timeEntryId = timeEntryId;
        }

        public String getRejectionReason() {
            return rejectionReason;
        }

        public void setRejectionReason(String rejectionReason) {
            this.rejectionReason = rejectionReason;
        }
    }

    public TimeEntryDecisionBatchRequest() {
    }

    public TimeEntryDecisionBatchRequest(List<Decision> decisions) {
        this.decisions = decisions;
    }

    public List<Decision> getDecisions() {
        return decisions;
    }

    public void setDecisions(List<Decision> decisions) {
        this.decisions = decisions;
    }
}

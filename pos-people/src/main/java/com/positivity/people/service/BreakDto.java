package com.positivity.people.service;

import java.time.Instant;

public class BreakDto {

    private Long sessionId;
    private Instant startedAt;
    private Instant endedAt;

    public BreakDto() {
    }

    public BreakDto(Long sessionId, Instant startedAt, Instant endedAt) {
        this.sessionId = sessionId;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Instant endedAt) {
        this.endedAt = endedAt;
    }
}
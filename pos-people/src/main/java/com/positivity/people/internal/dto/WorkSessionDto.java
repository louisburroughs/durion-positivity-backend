package com.positivity.people.internal.dto;

import java.time.Instant;

public class WorkSessionDto {

    private Long sessionId;
    private String personId;
    private String status;
    private Instant startedAt;
    private Instant endedAt;

    public WorkSessionDto() {
    }

    public WorkSessionDto(Long sessionId, String personId, String status, Instant startedAt, Instant endedAt) {
        this.sessionId = sessionId;
        this.personId = personId;
        this.status = status;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getPersonId() {
        return personId;
    }

    public void setPersonId(String personId) {
        this.personId = personId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
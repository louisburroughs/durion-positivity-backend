package com.positivity.people.internal.dto;

import java.time.Instant;
import java.util.UUID;

public class WorkSessionDto {

    private UUID sessionId;
    private UUID personId;
    private String status;
    private Instant startedAt;
    private Instant endedAt;

    public WorkSessionDto() {
    }

    public WorkSessionDto(UUID sessionId, UUID personId, String status, Instant startedAt, Instant endedAt) {
        this.sessionId = sessionId;
        this.personId = personId;
        this.status = status;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public void setSessionId(UUID sessionId) {
        this.sessionId = sessionId;
    }

    public UUID getPersonId() {
        return personId;
    }

    public void setPersonId(UUID personId) {
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

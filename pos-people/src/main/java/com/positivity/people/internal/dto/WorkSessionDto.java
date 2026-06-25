package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Work session representing a tracked period of work for a person")
public class WorkSessionDto {

    @Schema(
            description = "Work session identifier",
            example = "01960011-0000-7000-8000-000000000001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID sessionId;

    @Schema(
            description = "Person the session belongs to",
            example = "01960011-0000-7000-8000-000000000002",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID personId;

    @Schema(
            description = "Lifecycle status of the session",
            example = "OPEN",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String status;

    @Schema(
            description = "Timestamp the session started",
            example = "2026-02-16T08:00:00Z",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Instant startedAt;

    @Schema(
            description = "Timestamp the session ended",
            example = "2026-02-16T17:00:00Z",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Instant endedAt;

    @Schema(
            description = "Total billable minutes for the session",
            example = "480",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Integer billableMinutes;

    @Schema(
            description = "Total break minutes for the session",
            example = "30",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Integer breakMinutes;

    @Schema(
            description = "Timestamp the session was submitted",
            example = "2026-02-16T17:05:00Z",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Instant submittedAt;

    public WorkSessionDto() {}

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

    public Integer getBillableMinutes() {
        return billableMinutes;
    }

    public void setBillableMinutes(Integer billableMinutes) {
        this.billableMinutes = billableMinutes;
    }

    public Integer getBreakMinutes() {
        return breakMinutes;
    }

    public void setBreakMinutes(Integer breakMinutes) {
        this.breakMinutes = breakMinutes;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }
}

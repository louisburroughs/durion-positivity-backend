package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Break taken within a work session")
public class BreakDto {

    @Schema(description = "Work session the break belongs to", example = "01960011-0000-7000-8000-000000000001", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID sessionId;

    @Schema(description = "Timestamp the break started", example = "2026-02-16T12:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
    private Instant startedAt;

    @Schema(description = "Timestamp the break ended", example = "2026-02-16T12:30:00Z", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Instant endedAt;

    public BreakDto() {}

    public BreakDto(UUID sessionId, Instant startedAt, Instant endedAt) {
        this.sessionId = sessionId;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public void setSessionId(UUID sessionId) {
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

package com.positivity.people.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import com.positivity.shared.id.UUIDv7Generator;

@Entity
@Table(name = "work_session_break")
public class WorkSessionBreak {

    @Id
    @Column(name = "break_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID breakId;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "actor", length = 128)
    private String actor;

    @PrePersist
    void ensureId() {
        if (breakId == null) {
            breakId = UUIDv7Generator.generate();
        }
    }

    public UUID getBreakId() {
        return breakId;
    }

    public void setBreakId(UUID breakId) {
        this.breakId = breakId;
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

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }
}

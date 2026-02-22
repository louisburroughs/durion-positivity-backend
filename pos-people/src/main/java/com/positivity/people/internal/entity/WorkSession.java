package com.positivity.people.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;
import com.positivity.shared.id.UUIDv7Generator;

@Entity
@Table(name = "work_session")
@Data
public class WorkSession {

    @Id
    @Column(name = "session_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID sessionId;

    @Column(name = "person_id", nullable = false, length = 64)
    private String personId;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "actor", length = 128)
    private String actor;

    @PrePersist
    void ensureId() {
        if (sessionId == null) {
            sessionId = UUIDv7Generator.generate();
        }
    }

}

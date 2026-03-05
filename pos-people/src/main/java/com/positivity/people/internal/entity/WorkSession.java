package com.positivity.people.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;
import com.positivity.shared.id.UUIDv7Generator;

import jakarta.persistence.GeneratedValue;
import com.positivity.shared.id.UUIDv7Id;
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "work_session")
@Data
public class WorkSession {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "session_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID sessionId;

    @Column(name = "person_id", nullable = false, columnDefinition = "uuid")
    private UUID personId;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "actor", length = 128)
    private String actor;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void ensureId() {
    }

}

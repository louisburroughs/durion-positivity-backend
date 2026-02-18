package com.positivity.people.internal.entity;

import com.positivity.people.internal.enums.AssignmentTerminationPolicy;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "employee_offboarding_retry_queue")
@Getter
@Setter
public class EmployeeOffboardingRetry {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_policy", nullable = false)
    private AssignmentTerminationPolicy assignmentPolicy;

    @Column(name = "disable_reason")
    private String disableReason;

    @Column(name = "actor_id", nullable = false)
    private String actorId;

    @Column(name = "failure_reason", nullable = false)
    private String failureReason;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}

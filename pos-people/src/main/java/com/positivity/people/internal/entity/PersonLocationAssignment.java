package com.positivity.people.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "person_location_assignment")
@Getter
@Setter
public class PersonLocationAssignment {

    @Id
    @Column(name = "assignment_id", columnDefinition = "UUID", updatable = false, nullable = false)
    private UUID assignmentId;

    @Column(name = "location_id", columnDefinition = "UUID", nullable = false)
    private UUID locationId;

    @Column(name = "person_id", columnDefinition = "UUID", nullable = false)
    private UUID personId;

    @Column(name = "role", nullable = false, length = 100)
    private String role;

    @Column(name = "is_primary", nullable = false)
    private boolean isPrimary;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (assignmentId == null) {
            assignmentId = UUIDv7Generator.generate();
        }
    }
}

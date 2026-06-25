package com.positivity.people.internal.entity;

import com.positivity.people.internal.enums.EmployeeStatus;
import com.positivity.shared.id.UUIDv7Id;
import com.positivity.time.TimeSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Employment record for a {@link Person}. The person table holds identity (names, contact
 * blob); employment-specific attributes — employee number, status, hire/termination dates —
 * live here, one row per person ({@code person_id} is unique). Externally the employee is
 * still keyed by its {@code person_id} (see EmployeeService).
 */
@Entity
@Table(
        name = "employee",
        indexes = {
            @Index(name = "uq_employee_person", columnList = "person_id", unique = true),
            @Index(name = "idx_employee_status", columnList = "status")
        })
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Employee {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "person_id", nullable = false, unique = true)
    private UUID personId;

    @Column(name = "employee_number")
    private String employeeNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private EmployeeStatus status;

    @Column(name = "hire_date")
    private LocalDate hireDate;

    @Column(name = "termination_date")
    private LocalDate terminationDate;

    @Column(name = "status_effective_at")
    private Instant statusEffectiveAt;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    public void ensureStatusEffectiveAt() {
        if (status != null && statusEffectiveAt == null) {
            statusEffectiveAt = TimeSource.instant();
        }
    }
}

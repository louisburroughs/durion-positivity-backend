package com.positivity.people.internal.entity;

import com.positivity.people.internal.enums.AssignmentStatus;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Assignment of an {@link Employee} to a location for a role over a date range. References the
 * employment record ({@code employee_id}); the person id remains derivable via the employee for
 * API/back-compat ({@link #getPersonId()}).
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "employee_location_assignment",
        uniqueConstraints = @UniqueConstraint(columnNames = {"employee_id", "location_id", "role", "effective_from"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmployeeLocationAssignment {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    @NonNull
    private Employee employee;

    public UUID getEmployeeId() {
        return employee != null ? employee.getId() : null;
    }

    public UUID getPersonId() {
        return employee != null ? employee.getPersonId() : null;
    }

    @NonNull
    @NotNull
    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @NonNull
    @NotBlank
    @Column(name = "role", nullable = false)
    private String role;

    @Column(name = "is_primary", nullable = false)
    private boolean isPrimary;

    @NonNull
    @NotNull
    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private AssignmentStatus status = AssignmentStatus.ACTIVE;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "created_by", updatable = false)
    private String createdBy;
}

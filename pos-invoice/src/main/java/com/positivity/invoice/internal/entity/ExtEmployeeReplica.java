package com.positivity.invoice.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only employment replica fed by {@code people.events.v1} ({@code people.employee.updated},
 * ADR-0044 §6, #877). pos-people owns the facts; this module only resolves employee numbers to
 * active person ids for manager-approval elevation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ext_people_employee")
public class ExtEmployeeReplica {

    @Id
    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "person_id", nullable = false)
    private UUID personId;

    @Column(name = "employee_number", length = 64)
    private String employeeNumber;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** ArchUnit UUIDv7 rule hook (ADR-0013): the key is the owner's UUIDv7, stored verbatim. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}

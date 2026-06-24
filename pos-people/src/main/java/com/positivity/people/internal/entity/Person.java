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
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
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

@Entity
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Person {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @PrePersist
    public void generateId() {
        if (status != null && statusEffectiveAt == null) {
            statusEffectiveAt = TimeSource.instant();
        }
    }

    private String firstName;

    private String lastName;

    @Column(name = "legal_name")
    private String legalName;

    @Column(name = "preferred_name")
    private String preferredName;

    @Column(name = "employee_number")
    private String employeeNumber;

    @Enumerated(EnumType.STRING)
    private EmployeeStatus status;

    @Column(name = "hire_date")
    private LocalDate hireDate;

    @Column(name = "termination_date")
    private LocalDate terminationDate;

    @Lob
    @Column(name = "contact_info_json")
    private String contactInfoJson;

    @Column(name = "status_effective_at")
    private Instant statusEffectiveAt;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    // Email + phone numbers consolidated into person_contact_point (EMAIL / PHONE_WORK);
    // see PersonEmailService / PersonWorkPhoneService.

    /** Optional, validated externally - stick with username not userName */
    private String username;

    @PreUpdate
    public void ensureStatusEffectiveAt() {
        if (status != null && statusEffectiveAt == null) {
            statusEffectiveAt = TimeSource.instant();
        }
    }
}

package com.positivity.people.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import com.positivity.people.internal.enums.EmployeeStatus;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Person {
    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @PrePersist
    public void generateId() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
        if (status != null && statusEffectiveAt == null) {
            statusEffectiveAt = Instant.now();
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

    private String primaryEmail;
    private String secondaryEmail;

    @ElementCollection
    private List<String> phoneNumbers;

    /** Optional, validated externally - stick with username not userName */
    private String username;

    @PreUpdate
    public void ensureStatusEffectiveAt() {
        if (status != null && statusEffectiveAt == null) {
            statusEffectiveAt = Instant.now();
        }
    }
}

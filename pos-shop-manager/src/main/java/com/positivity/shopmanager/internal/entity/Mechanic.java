package com.positivity.shopmanager.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.shopmanager.internal.enums.MechanicStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "mechanic")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Mechanic {

    @Id
    @Column(name = "mechanic_id", columnDefinition = "UUID")
    private UUID mechanicId;

    @Column(name = "person_id")
    private String personId;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private MechanicStatus status;

    @Column(name = "hire_date")
    private LocalDate hireDate;

    @Column(name = "termination_date")
    private LocalDate terminationDate;

    @Column(name = "version")
    private int version;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @PrePersist
    public void generateMechanicId() {
        if (mechanicId == null) {
            mechanicId = UUIDv7Generator.generate();
        }
    }
}

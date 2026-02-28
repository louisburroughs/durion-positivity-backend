package com.positivity.shopmanager.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "mechanic_skill")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MechanicSkill {

    @Id
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    @Column(name = "mechanic_id", columnDefinition = "UUID")
    private UUID mechanicId;

    @Column(name = "skill_code")
    private String skillCode;

    @Column(name = "proficiency_level")
    private int proficiencyLevel;

    @Column(name = "certified_date")
    private LocalDate certifiedDate;

    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    @PrePersist
    public void generateId() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
    }
}

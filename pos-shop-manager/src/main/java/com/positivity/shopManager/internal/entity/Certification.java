package com.positivity.shopManager.internal.entity;

import jakarta.persistence.*;
import com.positivity.shared.id.UUIDv7Generator;
import lombok.*;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Certification {
    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @PrePersist
    public void generateId() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
    }

    private String aseCode; // ASE certification code
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "technician_id")
    private Technician technician;
}


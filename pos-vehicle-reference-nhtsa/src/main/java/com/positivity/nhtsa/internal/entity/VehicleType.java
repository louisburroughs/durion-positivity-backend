package com.positivity.nhtsa.internal.entity;

import jakarta.persistence.*;
import com.positivity.shared.id.UUIDv7Generator;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
public class VehicleType {
    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @PrePersist
    public void generateId() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
    }
    @ManyToOne
    @JoinColumn(name = "make_id")
    private Make make;
    private String vehicleTypeName;
    private String vehicleTypeId;
    private LocalDateTime cacheTimestamp;

}


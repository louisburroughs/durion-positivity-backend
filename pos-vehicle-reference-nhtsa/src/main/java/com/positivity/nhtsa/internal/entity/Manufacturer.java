package com.positivity.nhtsa.internal.entity;

import jakarta.persistence.*;
import com.positivity.shared.id.UUIDv7Generator;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
public class Manufacturer {
    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @PrePersist
    public void generateId() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
    }
    private String name;
    private LocalDateTime cacheTimestamp;
}


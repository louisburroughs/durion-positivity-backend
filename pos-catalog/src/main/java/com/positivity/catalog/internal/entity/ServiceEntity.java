package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.*;
import lombok.Data;

import java.util.UUID;

@Data
@Entity
@Table(name = "service")
public class ServiceEntity implements CatalogItem {
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
    private String longDescription;
    private String shortDescription;

    @Override
    public String getLongDescription() {
        return this.longDescription;
    }

    @Override
    public void setId(UUID id) {
        this.id = id;
    }
}

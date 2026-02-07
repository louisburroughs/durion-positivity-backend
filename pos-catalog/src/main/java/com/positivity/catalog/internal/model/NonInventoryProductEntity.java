package com.positivity.catalog.internal.model;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "non_inventory_product")
public class NonInventoryProductEntity implements CatalogItem {
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

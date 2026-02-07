package com.positivity.catalog.internal.model;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Slf4j
@NoArgsConstructor
@Entity
public class CatalogEntity {
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
    private String description;

    @OneToMany(fetch = FetchType.LAZY)
    private List<ProductEntity> products;

    @OneToMany(fetch = FetchType.LAZY)
    private List<ServiceEntity> services;

    @OneToMany(fetch = FetchType.LAZY)
    private List<NonInventoryProductEntity> nonInventoryProducts;

    public CatalogEntity(String name, String description, List<ProductEntity> products, List<ServiceEntity> services,
            List<NonInventoryProductEntity> nonInventoryProducts) {
        this.name = name;
        this.description = description;
        this.products = products;
        this.services = services;
        this.nonInventoryProducts = nonInventoryProducts;
    }
}

package com.positivity.catalog.internal.model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "service")
public class ServiceEntity implements CatalogItem {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;
    private String name;
    private String longDescription;
    private String shortDescription;

    @Override
    public String getLongDescription() {
        return this.longDescription;
    }

    @Override
    public void setId(Long id) {
        this.id = id;
    }
}

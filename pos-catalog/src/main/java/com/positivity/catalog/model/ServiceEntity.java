package com.positivity.catalog.model;

import com.positivity.catalog.CatalogItem;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@Entity
@Table(name = "service")
public class ServiceEntity implements CatalogItem{
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;
    private String name;
    private String longDescription;
    private String shortDescription;

}

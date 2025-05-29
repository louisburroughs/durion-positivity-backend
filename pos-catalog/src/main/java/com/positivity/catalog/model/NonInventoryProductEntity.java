package com.positivity.catalog.model;

import com.positivity.catalog.CatalogItem;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "non_inventory_product")
public class NonInventoryProductEntity implements CatalogItem {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;
    private String name;
    private String longDescription;
    private String shortDescription;


}

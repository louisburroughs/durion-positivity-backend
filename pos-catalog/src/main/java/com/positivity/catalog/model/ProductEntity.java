package com.positivity.catalog.model;

import com.positivity.catalog.CatalogItem;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
@Entity
@Table(name = "product")
public class ProductEntity implements CatalogItem {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;
    private String name;
    private String shortDescription;
    private String longDescription;
    private List<String> images;
    private String manufacturerPartNumber;
    private String manufacturerId;
    private String manufacturerName;
    private String manufacturerWarranty;
    private String manufacturerBrand;
    private String countryOfOrigin;
    private String sku;
    private String productCode;
    private ProductCodeType productCodeType;
    @ManyToOne(fetch = FetchType.EAGER)
    private Category category;
    @ManyToOne(fetch = FetchType.EAGER)
    private Subcategory subcategory;
    private String type;
    @OneToMany
    private List<DimensionEntity> dimensions;
    private String material;
    private String color;
    private String warranty;
    @Lob
    private String specifications;


}

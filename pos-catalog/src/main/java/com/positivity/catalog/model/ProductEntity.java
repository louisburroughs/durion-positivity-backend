package com.positivity.catalog.model;

import com.positivity.catalog.CatalogItem;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
@Entity
@Table(name = "product")
@Schema(description = "Represents a product in the catalog")
public class ProductEntity implements CatalogItem {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Schema(description = "Unique identifier of the product", example = "1")
    private Long id;

    @Schema(description = "Name of the product", example = "Heavy Duty Wrench")
    private String name;

    @Schema(description = "Short description of the product", example = "A versatile wrench for heavy-duty applications.")
    private String shortDescription;

    @Schema(description = "Detailed description of the product", example = "This heavy-duty wrench is made from hardened steel...")
    private String longDescription;

    @ElementCollection // Assuming images are a collection of strings (URLs or paths)
    @CollectionTable(name = "product_images", joinColumns = @JoinColumn(name = "product_id"))
    @Column(name = "image_url")
    @Schema(description = "List of image URLs for the product")
    private List<String> images;

    @Schema(description = "Manufacturer's part number", example = "HDW-001")
    private String manufacturerPartNumber;

    @Schema(description = "Identifier for the manufacturer", example = "MAN-123")
    private String manufacturerId;

    @Schema(description = "Name of the manufacturer", example = "Acme Tools")
    private String manufacturerName;

    @Schema(description = "Manufacturer's warranty information", example = "1 year limited warranty")
    private String manufacturerWarranty;

    @Schema(description = "Brand of the manufacturer", example = "AcmePro")
    private String manufacturerBrand;

    @Override
    public String getLongDescription() {
        return this.longDescription;
    }

    @Override
    public void setId(Long id) {
        this.id = id;
    }

    @Schema(description = "Country of origin for the product", example = "USA")
    private String countryOfOrigin;

    @Schema(description = "Stock Keeping Unit (SKU)", example = "SKU12345")
    private String sku;

    @Schema(description = "Product code (e.g., UPC, EAN)", example = "0123456789012")
    private String productCode;

    @Enumerated(EnumType.STRING)
    @Schema(description = "Type of the product code (e.g., UPC, EAN)", implementation = ProductCodeType.class)
    private ProductCodeType productCodeType;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "category_id")
    @Schema(description = "Category of the product")
    private Category category;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "subcategory_id")
    @Schema(description = "Subcategory of the product")
    private Subcategory subcategory;

    @Schema(description = "Type of the product (e.g., physical, digital)", example = "Physical")
    private String type;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "product_id") // This assumes a foreign key in DimensionEntity table
    @Schema(description = "List of dimensions for the product")
    private List<DimensionEntity> dimensions;

    @Schema(description = "Material of the product", example = "Steel")
    private String material;

    @Schema(description = "Color of the product", example = "Red")
    private String color;

    @Schema(description = "Warranty information for the product", example = "2 years parts and labor")
    private String warranty;

    @Lob
    @Schema(description = "Detailed specifications of the product", example = "{\"weight\": \"5kg\", \"length\": \"30cm\"}")
    private String specifications;


}

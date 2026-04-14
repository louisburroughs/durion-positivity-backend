package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Setter
@Getter
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "product",
        uniqueConstraints = {
            @UniqueConstraint(name = "uk_product_sku", columnNames = "sku"),
            @UniqueConstraint(
                    name = "uk_product_manufacturer_mpn",
                    columnNames = {"manufacturer_id", "manufacturer_part_number"})
        },
        indexes = {@Index(name = "idx_product_manufacturer_part_number", columnList = "manufacturer_part_number")})
@Schema(description = "Represents a product in the catalog")
public class ProductEntity implements CatalogItem {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    @Schema(description = "Unique identifier of the product", example = "018e1c9f-6b5a-7890-abcd-1234567890ab")
    private UUID id;

    @PrePersist
    public void generateId() {
        if (status == null) {
            status = ProductStatus.ACTIVE;
        }
        if (lifecycleState == null) {
            lifecycleState = ProductLifecycleState.ACTIVE;
        }
    }

    @Schema(description = "Name of the product", example = "Heavy Duty Wrench")
    private String name;

    @Schema(description = "Product description", example = "A versatile wrench for heavy-duty applications.")
    private String description;

    @Schema(description = "Operational product status", implementation = ProductStatus.class)
    @Enumerated(EnumType.STRING)
    private ProductStatus status = ProductStatus.ACTIVE;

    @Schema(description = "Unit of measure", example = "EA")
    private String unitOfMeasure;

    @Schema(
            description = "Short description of the product",
            example = "A versatile wrench for heavy-duty applications.")
    private String shortDescription;

    @Schema(
            description = "Detailed description of the product",
            example = "This heavy-duty wrench is made from hardened steel...")
    private String longDescription;

    @ElementCollection // Assuming images are a collection of strings (URLs or paths)
    @CollectionTable(name = "product_images", joinColumns = @JoinColumn(name = "product_id"))
    @org.hibernate.annotations.BatchSize(size = 50)
    @Column(name = "image_url")
    @Schema(description = "List of image URLs for the product")
    private List<String> images;

    @Schema(description = "Manufacturer's part number", example = "HDW-001")
    private String manufacturerPartNumber;

    @Schema(description = "Identifier for the manufacturer", example = "MAN-123")
    @Column(columnDefinition = "UUID")
    private UUID manufacturerId;

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
    public void setId(UUID id) {
        this.id = id;
    }

    @Schema(description = "Country of origin for the product", example = "USA")
    private String countryOfOrigin;

    @Schema(description = "Stock Keeping Unit (SKU)", example = "SKU12345")
    private String sku;

    @Schema(description = "Product code (e.g., UPC, EAN)", example = "0123456789012")
    private String productCode;

    @Schema(description = "UPC code for product", example = "0123456789012")
    private String upc;

    @Lob
    @Schema(description = "Arbitrary key-value product attributes stored as JSON")
    private String attributes;

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
    @Schema(
            description = "Detailed specifications of the product",
            example = "{\"weight\": \"5kg\", \"length\": \"30cm\"}")
    private String specifications;

    @Enumerated(EnumType.STRING)
    @Schema(description = "Lifecycle state of the product", implementation = ProductLifecycleState.class)
    private ProductLifecycleState lifecycleState = ProductLifecycleState.ACTIVE;

    @Schema(description = "UTC timestamp when lifecycle state takes effect")
    private Instant lifecycleStateEffectiveAt;

    @Column(columnDefinition = "UUID")
    @Schema(description = "User ID that last changed the lifecycle state")
    private UUID lastStateChangedBy;

    @Schema(description = "UTC timestamp when lifecycle state was last changed")
    private Instant lastStateChangedAt;

    @Schema(description = "Reason when discontinued override permission is used")
    private String lifecycleOverrideReason;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    @Schema(description = "Timestamp when product was created")
    private Instant createdAt;

    @Column(nullable = false)
    @LastModifiedDate
    @Schema(description = "Timestamp when product was last updated")
    private Instant updatedAt;

    @PostLoad
    public void ensureDefaults() {
        if (status == null) {
            status = ProductStatus.ACTIVE;
        }
        if (lifecycleState == null) {
            lifecycleState = ProductLifecycleState.ACTIVE;
        }
    }
}

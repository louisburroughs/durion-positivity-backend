package com.positivity.warranty.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.warranty.internal.enums.AppliesToType;
import com.positivity.warranty.internal.enums.CoverageType;
import com.positivity.warranty.internal.enums.ProrationMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Structured coverage terms — one row per distinct program (PRD §3.2). The policy must be in
 * effect on the <em>original sale date</em>; eligibility checks each structured term (PRD §6).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "warranty_policy",
        indexes = {
            @Index(name = "idx_wpolicy_provider", columnList = "provider_id"),
            @Index(name = "idx_wpolicy_coverage_type", columnList = "coverage_type")
        })
@EntityListeners(AuditingEntityListener.class)
public class WarrantyPolicy {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "coverage_type", nullable = false, length = 32)
    private CoverageType coverageType;

    @Enumerated(EnumType.STRING)
    @Column(name = "applies_to_type", nullable = false, length = 32)
    private AppliesToType appliesToType;

    /** Explicit pos-catalog productEntityId list when {@code appliesToType == PRODUCT_LIST}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "applies_to_product_entity_ids", columnDefinition = "jsonb")
    private List<UUID> appliesToProductEntityIds;

    /** pos-catalog category id when {@code appliesToType == CATEGORY}. */
    @Column(name = "applies_to_category_id")
    private UUID appliesToCategoryId;

    /** pos-catalog manufacturerId when {@code appliesToType == MANUFACTURER}. */
    @Column(name = "applies_to_manufacturer_id")
    private UUID appliesToManufacturerId;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    /** Time limit in months from sale date; null = unlimited. */
    @Column(name = "duration_months")
    private Integer durationMonths;

    /** Miles from sale odometer; null = n/a. */
    @Column(name = "mileage_limit")
    private Integer mileageLimit;

    /** Tires: coverage ends at this remaining depth, in 32nds of an inch (industry default 2). */
    @Column(name = "tread_pull_point_thirty_seconds")
    private Integer treadPullPointThirtySeconds;

    @Builder.Default
    @Column(name = "labor_covered", nullable = false)
    private Boolean laborCovered = false;

    @Column(name = "labor_hours_cap", precision = 9, scale = 2)
    private BigDecimal laborHoursCap;

    @Column(name = "labor_rate_cap", precision = 19, scale = 4)
    private BigDecimal laborRateCap;

    @Enumerated(EnumType.STRING)
    @Column(name = "proration_method", nullable = false, length = 32)
    private ProrationMethod prorationMethod;

    @Builder.Default
    @Column(name = "requires_part_return", nullable = false)
    private Boolean requiresPartReturn = false;

    @Builder.Default
    @Column(name = "requires_photo_evidence", nullable = false)
    private Boolean requiresPhotoEvidence = false;

    @Builder.Default
    @Column(name = "transferable", nullable = false)
    private Boolean transferable = false;

    @Column(name = "terms_text", columnDefinition = "text")
    private String termsText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "document_urls", columnDefinition = "jsonb")
    private List<String> documentUrls;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by")
    private String updatedBy;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}

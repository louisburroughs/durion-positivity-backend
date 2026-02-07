package com.positivity.accounting.internal.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * GL Mapping - effective-dated mapping from (PostingCategory, MappingKey) to
 * GLAccount.
 * 
 * Immutable once created (append-only). Backend rejects overlapping effective
 * date ranges
 * for same (postingCategoryId, mappingKeyId) combination.
 * 
 * Query pattern: effectiveFrom <= transactionDate < effectiveTo (or effectiveTo
 * is null)
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - GL Mapping</a>
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@Table(name = "gl_mapping", indexes = {
        @Index(name = "idx_gl_mapping_category_key", columnList = "posting_category_id, mapping_key_id"),
        @Index(name = "idx_gl_mapping_effective_from", columnList = "effective_start_date"),
        @Index(name = "idx_gl_mapping_effective_to", columnList = "effective_end_date"),
        @Index(name = "idx_gl_mapping_gl_account", columnList = "gl_account_id"),
        @Index(name = "idx_gl_mapping_source_code", columnList = "source_system, external_code")
})
public class GLMapping {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "gl_mapping_id", nullable = false)
    private UUID glMappingId;

    @Column(name = "source_system", length = 50, nullable = false)
    private String sourceSystem;

    @Column(name = "external_code", length = 100, nullable = false)
    private String externalCode;

    @Column(name = "posting_category_id", nullable = false)
    private UUID postingCategoryId;

    @Column(name = "mapping_key_id", nullable = false)
    private UUID mappingKeyId;

    @Column(name = "gl_account_id", nullable = false)
    private UUID glAccountId;

    @Column(name = "effective_start_date", nullable = false)
    private LocalDateTime effectiveStartDate;

    @Column(name = "effective_end_date")
    private LocalDateTime effectiveEndDate;

    /**
     * Dimensions for GL posting (businessUnitId, locationId, departmentId,
     * costCenterId).
     * Stored as JSON (H2) or JSONB (PostgreSQL) based on dialect.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dimensions")
    private Map<String, String> dimensions;

    // Audit fields (immutable after creation)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 50, nullable = false, updatable = false)
    private String createdBy;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    /**
     * Check if this mapping is effective for a given transaction date.
     * 
     * @param transactionDate The date to check
     * @return true if effectiveStartDate <= transactionDate < effectiveEndDate (or
     *         effectiveEndDate is null)
     */
    @Transient
    public boolean isEffectiveOn(LocalDateTime transactionDate) {
        if (transactionDate == null) {
            return false;
        }

        boolean afterStart = !transactionDate.isBefore(effectiveStartDate);
        boolean beforeEnd = effectiveEndDate == null || transactionDate.isBefore(effectiveEndDate);

        return afterStart && beforeEnd;
    }
}

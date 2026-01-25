package com.positivity.accounting.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

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
@Entity
@Table(name = "gl_mapping", indexes = {
        @Index(name = "idx_gl_mapping_category_key", columnList = "posting_category_id, mapping_key_id"),
        @Index(name = "idx_gl_mapping_effective_from", columnList = "effective_from"),
        @Index(name = "idx_gl_mapping_effective_to", columnList = "effective_to"),
        @Index(name = "idx_gl_mapping_gl_account", columnList = "gl_account_id")
})
public class GLMapping {

    @Id
    @Column(name = "gl_mapping_id", length = 50, nullable = false)
    private String glMappingId;

    @Column(name = "posting_category_id", length = 50, nullable = false)
    private String postingCategoryId;

    @Column(name = "mapping_key_id", length = 50, nullable = false)
    private String mappingKeyId;

    @Column(name = "gl_account_id", length = 50, nullable = false)
    private String glAccountId;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    /**
     * Dimensions for GL posting (businessUnitId, locationId, departmentId,
     * costCenterId).
     * Stored as JSONB/JSON for flexibility.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dimensions", columnDefinition = "jsonb")
    private Map<String, String> dimensions;

    // Audit fields (immutable after creation)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 50, nullable = false, updatable = false)
    private String createdBy;

    // Constructors
    public GLMapping() {
    }

    // Getters and Setters
    public String getGlMappingId() {
        return glMappingId;
    }

    public void setGlMappingId(String glMappingId) {
        this.glMappingId = glMappingId;
    }

    public String getPostingCategoryId() {
        return postingCategoryId;
    }

    public void setPostingCategoryId(String postingCategoryId) {
        this.postingCategoryId = postingCategoryId;
    }

    public String getMappingKeyId() {
        return mappingKeyId;
    }

    public void setMappingKeyId(String mappingKeyId) {
        this.mappingKeyId = mappingKeyId;
    }

    public String getGlAccountId() {
        return glAccountId;
    }

    public void setGlAccountId(String glAccountId) {
        this.glAccountId = glAccountId;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(LocalDate effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(LocalDate effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public Map<String, String> getDimensions() {
        return dimensions;
    }

    public void setDimensions(Map<String, String> dimensions) {
        this.dimensions = dimensions;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    /**
     * Check if this mapping is effective for a given transaction date.
     * 
     * @param transactionDate The date to check
     * @return true if effectiveFrom <= transactionDate < effectiveTo (or
     *         effectiveTo is null)
     */
    @Transient
    public boolean isEffectiveOn(LocalDate transactionDate) {
        if (transactionDate == null) {
            return false;
        }

        boolean afterStart = !transactionDate.isBefore(effectiveFrom);
        boolean beforeEnd = effectiveTo == null || transactionDate.isBefore(effectiveTo);

        return afterStart && beforeEnd;
    }
}

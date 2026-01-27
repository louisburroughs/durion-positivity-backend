package com.positivity.accounting.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.time.LocalDateTime;
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
        @Index(name = "idx_gl_mapping_effective_from", columnList = "effective_start_date"),
        @Index(name = "idx_gl_mapping_effective_to", columnList = "effective_end_date"),
        @Index(name = "idx_gl_mapping_gl_account", columnList = "gl_account_id"),
        @Index(name = "idx_gl_mapping_source_code", columnList = "source_system, external_code")
})
public class GLMapping {

    @Id
    @Column(name = "gl_mapping_id", length = 50, nullable = false)
    private String glMappingId;

    @Column(name = "source_system", length = 50, nullable = false)
    private String sourceSystem;

    @Column(name = "external_code", length = 100, nullable = false)
    private String externalCode;

    @Column(name = "posting_category_id", length = 50, nullable = false)
    private String postingCategoryId;

    @Column(name = "mapping_key_id", length = 50, nullable = false)
    private String mappingKeyId;

    @Column(name = "gl_account_id", length = 50, nullable = false)
    private String glAccountId;

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

    public String getSourceSystem() {
        return sourceSystem;
    }

    public void setSourceSystem(String sourceSystem) {
        this.sourceSystem = sourceSystem;
    }

    public String getExternalCode() {
        return externalCode;
    }

    public void setExternalCode(String externalCode) {
        this.externalCode = externalCode;
    }

    public String getGlAccountId() {
        return glAccountId;
    }

    public void setGlAccountId(String glAccountId) {
        this.glAccountId = glAccountId;
    }

    public LocalDateTime getEffectiveStartDate() {
        return effectiveStartDate;
    }

    public void setEffectiveStartDate(LocalDateTime effectiveStartDate) {
        this.effectiveStartDate = effectiveStartDate;
    }

    public LocalDateTime getEffectiveEndDate() {
        return effectiveEndDate;
    }

    public void setEffectiveEndDate(LocalDateTime effectiveEndDate) {
        this.effectiveEndDate = effectiveEndDate;
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

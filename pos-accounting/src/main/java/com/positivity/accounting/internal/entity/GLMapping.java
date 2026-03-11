package com.positivity.accounting.internal.entity;

import jakarta.persistence.*;
import com.positivity.shared.id.UUIDv7Id;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
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
@ToString(exclude = { "postingCategory", "mappingKey", "glAccount" })
@Entity
@EntityListeners(AuditingEntityListener.class)
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
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "gl_mapping_id", nullable = false, columnDefinition = "UUID")
    private UUID glMappingId;
    @Column(name = "source_system", length = 50, nullable = false)
    private String sourceSystem;

    @Column(name = "external_code", length = 100, nullable = false)
    private String externalCode;

    /**
     * Optional: used for category-based mapping strategy.
     * Null when using source system + external code strategy.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "posting_category_id")
    private PostingCategory postingCategory;

    /**
     * Optional: used for key-based mapping strategy.
     * Null when using source system + external code strategy.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mapping_key_id")
    private MappingKey mappingKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "gl_account_id", nullable = false)
    private GLAccount glAccount;

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
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 50, nullable = false, updatable = false)
    private String createdBy;

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

    @Transient
    public UUID getPostingCategoryId() {
        return postingCategory != null ? postingCategory.getPostingCategoryId() : null;
    }

    public void setPostingCategoryId(UUID postingCategoryId) {
        this.postingCategory = postingCategoryId == null ? null : new PostingCategory(postingCategoryId);
    }

    @Transient
    public UUID getMappingKeyId() {
        return mappingKey != null ? mappingKey.getMappingKeyId() : null;
    }

    public void setMappingKeyId(UUID mappingKeyId) {
        this.mappingKey = mappingKeyId == null ? null : new MappingKey(mappingKeyId);
    }

    @Transient
    public UUID getGlAccountId() {
        return glAccount != null ? glAccount.getGlAccountId() : null;
    }

    public void setGlAccountId(UUID glAccountId) {
        this.glAccount = glAccountId == null ? null : new GLAccount(glAccountId);
    }
}

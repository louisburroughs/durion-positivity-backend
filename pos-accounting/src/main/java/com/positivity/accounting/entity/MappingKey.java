package com.positivity.accounting.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Mapping Key - second level of GL mapping taxonomy hierarchy.
 * 
 * Each key belongs to a PostingCategory and can have multiple GLMappings.
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - GL Mapping</a>
 */
@Entity
@Table(name = "mapping_key", indexes = {
        @Index(name = "idx_mapping_key_category", columnList = "posting_category_id"),
        @Index(name = "idx_mapping_key_name", columnList = "key_name")
})
public class MappingKey {

    @Id
    @Column(name = "mapping_key_id", length = 50, nullable = false)
    private String mappingKeyId;

    @Column(name = "posting_category_id", length = 50, nullable = false)
    private String postingCategoryId;

    @Column(name = "key_name", length = 100, nullable = false)
    private String keyName;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    // Audit fields
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 50, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "modified_at", nullable = false)
    private Instant modifiedAt;

    @Column(name = "modified_by", length = 50, nullable = false)
    private String modifiedBy;

    // Constructors
    public MappingKey() {
    }

    // Getters and Setters
    public String getMappingKeyId() {
        return mappingKeyId;
    }

    public void setMappingKeyId(String mappingKeyId) {
        this.mappingKeyId = mappingKeyId;
    }

    public String getPostingCategoryId() {
        return postingCategoryId;
    }

    public void setPostingCategoryId(String postingCategoryId) {
        this.postingCategoryId = postingCategoryId;
    }

    public String getKeyName() {
        return keyName;
    }

    public void setKeyName(String keyName) {
        this.keyName = keyName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
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

    public Instant getModifiedAt() {
        return modifiedAt;
    }

    public void setModifiedAt(Instant modifiedAt) {
        this.modifiedAt = modifiedAt;
    }

    public String getModifiedBy() {
        return modifiedBy;
    }

    public void setModifiedBy(String modifiedBy) {
        this.modifiedBy = modifiedBy;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.modifiedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.modifiedAt = Instant.now();
    }
}

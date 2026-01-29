package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.enums.PostingRuleSetState;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Posting Rule Version - versioned posting rules for JE generation.
 * 
 * State machine: DRAFT → PUBLISHED → ARCHIVED
 * - DRAFT: Editable, not used for JE generation
 * - PUBLISHED: Immutable, active for JE generation
 * - ARCHIVED: Immutable, inactive (historical)
 * 
 * rulesDefinition contains JSON with conditions and GL posting logic.
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - Posting Rules</a>
 */
@Entity
@Table(name = "posting_rule_version", uniqueConstraints = {
        @UniqueConstraint(name = "uk_posting_rule_set_version", columnNames = { "posting_rule_set_id",
                "version_number" })
}, indexes = {
        @Index(name = "idx_posting_rule_version_set", columnList = "posting_rule_set_id"),
        @Index(name = "idx_posting_rule_version_state", columnList = "state")
})
public class PostingRuleVersion {

    @Id
    @Column(name = "version_id", length = 50, nullable = false)
    private String versionId;

    @Column(name = "posting_rule_set_id", length = 50, nullable = false)
    private String postingRuleSetId;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", length = 20, nullable = false)
    private PostingRuleSetState state = PostingRuleSetState.DRAFT;

    /**
     * JSON containing posting rules definition.
     * Schema: {"conditions": [{"condition": "...", "lines": [...]}]}
     */
    @Column(name = "rules_definition", columnDefinition = "TEXT", nullable = false)
    private String rulesDefinition;

    // Audit fields
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 50, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "modified_at", nullable = false)
    private Instant modifiedAt;

    @Column(name = "modified_by", length = 50, nullable = false)
    private String modifiedBy;

    // State transition audit
    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "published_by", length = 50)
    private String publishedBy;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "archived_by", length = 50)
    private String archivedBy;

    // Constructors
    public PostingRuleVersion() {
    }

    // Getters and Setters
    public String getVersionId() {
        return versionId;
    }

    public void setVersionId(String versionId) {
        this.versionId = versionId;
    }

    public String getPostingRuleSetId() {
        return postingRuleSetId;
    }

    public void setPostingRuleSetId(String postingRuleSetId) {
        this.postingRuleSetId = postingRuleSetId;
    }

    public Integer getVersionNumber() {
        return versionNumber;
    }

    public void setVersionNumber(Integer versionNumber) {
        this.versionNumber = versionNumber;
    }

    public PostingRuleSetState getState() {
        return state;
    }

    public void setState(PostingRuleSetState state) {
        this.state = state;
    }

    public String getRulesDefinition() {
        return rulesDefinition;
    }

    public void setRulesDefinition(String rulesDefinition) {
        this.rulesDefinition = rulesDefinition;
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

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public String getPublishedBy() {
        return publishedBy;
    }

    public void setPublishedBy(String publishedBy) {
        this.publishedBy = publishedBy;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(Instant archivedAt) {
        this.archivedAt = archivedAt;
    }

    public String getArchivedBy() {
        return archivedBy;
    }

    public void setArchivedBy(String archivedBy) {
        this.archivedBy = archivedBy;
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

    /**
     * Check if this version is immutable (cannot be edited).
     * 
     * @return true if state is PUBLISHED or ARCHIVED
     */
    @Transient
    public boolean isImmutable() {
        return state == PostingRuleSetState.PUBLISHED || state == PostingRuleSetState.ARCHIVED;
    }
}

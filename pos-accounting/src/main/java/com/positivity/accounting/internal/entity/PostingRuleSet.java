package com.positivity.accounting.internal.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Posting Rule Set - parent entity for versioned posting rules.
 * 
 * Each rule set has multiple versions (PostingRuleVersion). Only one version
 * can be PUBLISHED at a time for active JE generation.
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - Posting Rules</a>
 */
@Entity
@Table(name = "posting_rule_set", indexes = {
        @Index(name = "idx_posting_rule_set_name", columnList = "name"),
        @Index(name = "idx_posting_rule_set_event_type", columnList = "event_type")
})
public class PostingRuleSet {

    @Id
    @Column(name = "posting_rule_set_id", length = 50, nullable = false)
    private String postingRuleSetId;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "event_type", length = 100, nullable = false)
    private String eventType;

    @Column(name = "description", length = 500)
    private String description;

    @OneToMany(mappedBy = "postingRuleSetId", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("versionNumber DESC")
    private List<PostingRuleVersion> versions = new ArrayList<>();

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
    public PostingRuleSet() {
    }

    // Getters and Setters
    public String getPostingRuleSetId() {
        return postingRuleSetId;
    }

    public void setPostingRuleSetId(String postingRuleSetId) {
        this.postingRuleSetId = postingRuleSetId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<PostingRuleVersion> getVersions() {
        return versions;
    }

    public void setVersions(List<PostingRuleVersion> versions) {
        this.versions = versions;
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

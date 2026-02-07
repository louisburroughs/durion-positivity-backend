package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.enums.PostingRuleSetState;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

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
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@Table(name = "posting_rule_version", uniqueConstraints = {
        @UniqueConstraint(name = "uk_posting_rule_set_version", columnNames = { "posting_rule_set_id",
                "version_number" })
}, indexes = {
        @Index(name = "idx_posting_rule_version_set", columnList = "posting_rule_set_id"),
        @Index(name = "idx_posting_rule_version_state", columnList = "state")
})
public class PostingRuleVersion {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "version_id", nullable = false)
    private UUID versionId;

    @Column(name = "posting_rule_set_id", nullable = false)
    private UUID postingRuleSetId;

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

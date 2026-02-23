package com.positivity.accounting.internal.entity;

import jakarta.persistence.*;
import com.positivity.shared.id.UUIDv7Id;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

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
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(exclude = "versions")
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "posting_rule_set", indexes = {
        @Index(name = "idx_posting_rule_set_name", columnList = "name"),
        @Index(name = "idx_posting_rule_set_event_type", columnList = "event_type")
})
public class PostingRuleSet {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "posting_rule_set_id", nullable = false, columnDefinition = "UUID")
    private UUID postingRuleSetId;

    @PrePersist
    public void onPrePersist() {
    }

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "event_type", length = 100, nullable = false)
    private String eventType;

    @Column(name = "description", length = 500)
    private String description;

    @OneToMany(mappedBy = "postingRuleSet", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("versionNumber DESC")
    private List<PostingRuleVersion> versions = new ArrayList<>();

    // Audit fields
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 50, nullable = false, updatable = false)
    private String createdBy;

    @LastModifiedDate
    @Column(name = "modified_at", nullable = false)
    private Instant modifiedAt;

    @Column(name = "modified_by", length = 50, nullable = false)
    private String modifiedBy;

}

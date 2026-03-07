package com.positivity.accounting.internal.entity;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Id;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Default GL Account Mapping for event types without explicit posting rules.
 * Provides fallback debit/credit GL accounts to prevent event processing
 * failures.
 * 
 * Resolution priority:
 * 1. PostingRuleVersion (explicit rules)
 * 2. DefaultGLMapping with matching organizationId
 * 3. DefaultGLMapping with NULL organizationId (global default)
 * 4. Fail with UNMAPPED_EVENT_TYPE
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - Default GL Mapping</a>
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "default_gl_mapping", indexes = {
        @Index(name = "idx_default_gl_mapping_event_type", columnList = "event_type, organization_id"),
        @Index(name = "idx_default_gl_mapping_active", columnList = "active")
})
public class DefaultGLMapping {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "mapping_id", nullable = false, columnDefinition = "UUID")
    private UUID mappingId;

    @PrePersist
    public void onPrePersist() {
        String currentUser = SecurityContextHelper.isAuthenticated()
                ? SecurityContextHelper.getCurrentUsernameOrDefault("SYSTEM")
                : "SYSTEM";
        this.createdBy = currentUser;
        this.modifiedBy = currentUser;
    }

    @PreUpdate
    public void onPreUpdate() {
        String currentUser = SecurityContextHelper.isAuthenticated()
                ? SecurityContextHelper.getCurrentUsernameOrDefault("SYSTEM")
                : "SYSTEM";
        this.modifiedBy = currentUser;
    }

    @Column(name = "event_type", length = 100, nullable = false)
    private String eventType;

    /**
     * Organization-specific default. NULL = applies to all organizations (global
     * default).
     */
    @Nullable
    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "debit_account_id", nullable = false)
    private UUID debitAccountId;

    @Column(name = "credit_account_id", nullable = false)
    private UUID creditAccountId;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 100, nullable = false, updatable = false)
    private String createdBy;

    @LastModifiedDate
    @Column(name = "modified_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "modified_by", length = 100, nullable = false)
    private String modifiedBy;
}

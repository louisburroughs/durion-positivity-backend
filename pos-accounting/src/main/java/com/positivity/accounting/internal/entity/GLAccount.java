package com.positivity.accounting.internal.entity;

import java.time.Clock;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.data.domain.Persistable;

import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.shared.id.UUIDv7Generator;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import jakarta.persistence.GeneratedValue;
import com.positivity.shared.id.UUIDv7Id;
/**
 * General Ledger Account entity.
 * 
 * Status is derived from activationDate and deactivationDate, not stored:
 * - ACTIVE: activationDate <= today < deactivationDate (or null)
 * - INACTIVE: deactivationDate <= today
 * - NOT_YET_ACTIVE: activationDate > today
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - GLAccount</a>
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "gl_account", indexes = {
        @Index(name = "idx_account_code", columnList = "account_code", unique = true),
        @Index(name = "idx_account_type", columnList = "account_type"),
        @Index(name = "idx_activation_date", columnList = "activation_date"),
        @Index(name = "idx_deactivation_date", columnList = "deactivation_date")
})
public class GLAccount implements Persistable<UUID> {

    /**
     * Transient flag used by Spring Data JPA to distinguish new entities from
     * existing ones. Required because the entity uses an assigned/pre-set ID
     * combined with an initialized {@code @Version} field, which would
     * otherwise cause {@code save()} to call {@code merge()} instead of
     * {@code persist()}.
     */
    @Transient
    private boolean isNew = true;

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "gl_account_id", nullable = false)
    private UUID glAccountId;

    @Column(name = "account_code", length = 20, nullable = false, unique = true)
    private String accountCode;

    @Column(name = "account_name", length = 100, nullable = false)
    private String accountName;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", length = 20, nullable = false)
    private AccountType accountType;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "parent_account_id")
    private UUID parentAccountId;

    @Column(name = "activation_date")
    private LocalDateTime activationDate;

    @Column(name = "deactivation_date")
    private LocalDateTime deactivationDate;

    // Audit fields
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 50, nullable = false, updatable = false)
    private String createdBy;

    @LastModifiedDate
    @Column(name = "modified_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "modified_by", length = 50, nullable = false)
    private String modifiedBy;

    // Optimistic locking
    @Version
    @Column(name = "version")
    private Integer version = 0;

    @Override
    public UUID getId() {
        return glAccountId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    // Lifecycle callbacks
    @PrePersist
    protected void onCreate() {
if (this.activationDate == null) {
            this.activationDate = LocalDateTime.now(Clock.systemUTC());
        }
    }

    @PostPersist
    @PostLoad
    protected void markNotNew() {
        this.isNew = false;
    }
    /**
     * Derive account status from activation and deactivation dates.
     * 
     * @return "ACTIVE", "INACTIVE", or "NOT_YET_ACTIVE"
     */
    @Transient
    public String getDerivedStatus() {
        LocalDateTime today = LocalDateTime.now(Clock.systemUTC());

        if (activationDate != null && activationDate.isAfter(today)) {
            return "NOT_YET_ACTIVE";
        }

        if (deactivationDate != null && !deactivationDate.isAfter(today)) {
            return "INACTIVE";
        }

        return "ACTIVE";
    }
}

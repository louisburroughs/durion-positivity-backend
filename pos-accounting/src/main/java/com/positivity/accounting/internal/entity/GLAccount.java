package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.enums.AccountType;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

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
@Table(name = "gl_account", indexes = {
        @Index(name = "idx_account_code", columnList = "account_code", unique = true),
        @Index(name = "idx_account_type", columnList = "account_type"),
        @Index(name = "idx_activation_date", columnList = "activation_date"),
        @Index(name = "idx_deactivation_date", columnList = "deactivation_date")
})
public class GLAccount {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
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
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 50, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "modified_at", nullable = false)
    private Instant modifiedAt;

    @Column(name = "modified_by", length = 50, nullable = false)
    private String modifiedBy;

    // Optimistic locking
    @Version
    @Column(name = "version")
    private Integer version;

    // Lifecycle callbacks
    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.modifiedAt = now;
        if (this.activationDate == null) {
            this.activationDate = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.modifiedAt = Instant.now();
    }

    /**
     * Derive account status from activation and deactivation dates.
     * 
     * @return "ACTIVE", "INACTIVE", or "NOT_YET_ACTIVE"
     */
    @Transient
    public String getDerivedStatus() {
        LocalDateTime today = LocalDateTime.now();

        if (activationDate != null && activationDate.isAfter(today)) {
            return "NOT_YET_ACTIVE";
        }

        if (deactivationDate != null && !deactivationDate.isAfter(today)) {
            return "INACTIVE";
        }

        return "ACTIVE";
    }
}

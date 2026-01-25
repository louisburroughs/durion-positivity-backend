package com.positivity.accounting.entity;

import com.positivity.accounting.enums.AccountType;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

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
@Entity
@Table(name = "gl_account", indexes = {
        @Index(name = "idx_account_code", columnList = "account_code", unique = true),
        @Index(name = "idx_account_type", columnList = "account_type"),
        @Index(name = "idx_activation_date", columnList = "activation_date"),
        @Index(name = "idx_deactivation_date", columnList = "deactivation_date")
})
public class GLAccount {

    @Id
    @Column(name = "gl_account_id", length = 50, nullable = false)
    private String glAccountId;

    @Column(name = "account_code", length = 20, nullable = false, unique = true)
    private String accountCode;

    @Column(name = "account_name", length = 100, nullable = false)
    private String accountName;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", length = 20, nullable = false)
    private AccountType accountType;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "parent_account_id", length = 50)
    private String parentAccountId;

    @Column(name = "activation_date")
    private LocalDate activationDate;

    @Column(name = "deactivation_date")
    private LocalDate deactivationDate;

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

    // Constructors
    public GLAccount() {
    }

    // Getters and Setters
    public String getGlAccountId() {
        return glAccountId;
    }

    public void setGlAccountId(String glAccountId) {
        this.glAccountId = glAccountId;
    }

    public String getAccountCode() {
        return accountCode;
    }

    public void setAccountCode(String accountCode) {
        this.accountCode = accountCode;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public void setAccountType(AccountType accountType) {
        this.accountType = accountType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getParentAccountId() {
        return parentAccountId;
    }

    public void setParentAccountId(String parentAccountId) {
        this.parentAccountId = parentAccountId;
    }

    public LocalDate getActivationDate() {
        return activationDate;
    }

    public void setActivationDate(LocalDate activationDate) {
        this.activationDate = activationDate;
    }

    public LocalDate getDeactivationDate() {
        return deactivationDate;
    }

    public void setDeactivationDate(LocalDate deactivationDate) {
        this.deactivationDate = deactivationDate;
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

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    // Lifecycle callbacks
    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.modifiedAt = now;
        if (this.activationDate == null) {
            this.activationDate = LocalDate.now();
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
        LocalDate today = LocalDate.now();

        if (activationDate != null && activationDate.isAfter(today)) {
            return "NOT_YET_ACTIVE";
        }

        if (deactivationDate != null && !deactivationDate.isAfter(today)) {
            return "INACTIVE";
        }

        return "ACTIVE";
    }
}

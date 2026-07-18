package com.positivity.accounting.internal.entity;

import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Accounting Configuration - org-level key/value configuration store
 * (story B2, issue #944).
 *
 * <p>First key: {@code HARD_LOCK_DATE} — the single org-level hard-lock date
 * (ISO {@code yyyy-MM-dd}). No posting is ever allowed with a transaction
 * date strictly before it, with no override path. The date only moves
 * forward (monotonic), which is what makes the lock irreversible.
 *
 * <p>The table ships empty; keys appear on first write through
 * {@link com.positivity.accounting.service.AccountingConfigurationService}.
 *
 * @see <a href=
 *      "domains/accounting/plan-odoo-parity-pos-accounting.md">Odoo Parity Plan -
 *      Story B2</a>
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "accounting_configuration",
        uniqueConstraints = {@UniqueConstraint(name = "uq_accounting_configuration_key", columnNames = "config_key")})
public class AccountingConfiguration {

    private static final String SYSTEM = "SYSTEM";

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "config_id", nullable = false, columnDefinition = "UUID")
    private UUID configId;

    /**
     * Configuration key, e.g. {@code HARD_LOCK_DATE}. Unique.
     */
    @Column(name = "config_key", length = 100, nullable = false)
    private String configKey;

    /**
     * Configuration value serialized as a string (dates in ISO
     * {@code yyyy-MM-dd}).
     */
    @Column(name = "config_value", length = 500, nullable = false)
    private String configValue;

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

    @PrePersist
    public void onPrePersist() {
        String currentUser = resolveActor();
        this.createdBy = currentUser;
        this.modifiedBy = currentUser;
    }

    @PreUpdate
    protected void onUpdate() {
        this.modifiedBy = resolveActor();
    }

    private static String resolveActor() {
        return SecurityContextHelper.isAuthenticated()
                ? SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM)
                : SYSTEM;
    }
}

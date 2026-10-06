package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.enums.TemplateEntryKind;
import com.positivity.accounting.internal.enums.TemplateEntryOutcome;
import com.positivity.accounting.internal.enums.TemplateEntryReason;
import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
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
 * What happened to one accounting template entry in one tenant (#2526).
 *
 * <p>{@link #entryKey} is the applier's natural key ({@code ACCOUNT:1000},
 * {@code GL_MAPPING:INVOICE_REVENUE/SERVICE_REVENUE}). {@link #templateValue} and
 * {@link #tenantValue} are business text for the status read, never ids. {@link #targetRowId}
 * names the tenant row the entry was created as or adopted to; it is not a foreign key, because a
 * tenant may delete that row and the record of what was applied must outlive it.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "accounting_template_entry",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_accounting_template_entry_key",
                    columnNames = {"tenant_id", "entry_key"})
        },
        indexes = {@Index(name = "accounting_template_entry_outcome_idx", columnList = "tenant_id, outcome")})
public class AccountingTemplateEntry extends TenantScopedEntity {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "entry_id", nullable = false, columnDefinition = "UUID")
    private UUID entryId;

    @Column(name = "entry_key", length = 300, nullable = false, updatable = false)
    private String entryKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 30, nullable = false, updatable = false)
    private TemplateEntryKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 20, nullable = false)
    private TemplateEntryOutcome outcome;

    /** Set only while the outcome is {@code CONFLICT} or {@code WITHHELD}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 30)
    private TemplateEntryReason reason;

    /** Fingerprint of the template entry as it was when this outcome was recorded. */
    @Column(name = "entry_fingerprint", length = 64, nullable = false)
    private String entryFingerprint;

    @Column(name = "target_row_id")
    private UUID targetRowId;

    @Column(name = "template_value", columnDefinition = "TEXT", nullable = false)
    private String templateValue;

    @Column(name = "tenant_value", columnDefinition = "TEXT")
    private String tenantValue;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "modified_at", nullable = false)
    private Instant updatedAt;
}

package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.enums.RegisterFloatChangeKind;
import com.positivity.accounting.internal.enums.RegisterFloatRelocationReason;
import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
 * One change of a register's float (#2511): a {@code GO_LIVE} or {@code CHANGE} row owns the journal
 * entry that moved 1080, a {@code REVERSAL} row records the reversal of one of them. A GO_LIVE or
 * CHANGE row whose entry was reversed carries {@code reversalJournalEntryId} and no longer counts. A
 * {@code RELOCATION} row (#2571, AW32) moved the register from {@code previousLocationId} to {@code
 * locationId} with its float unchanged; it owns the reclass entry, or none for a zero float.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "register_float_change",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_register_float_change_request",
                    columnNames = {"tenant_id", "request_id"})
        })
public class RegisterFloatChange extends TenantScopedEntity {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "change_id", nullable = false, columnDefinition = "UUID")
    private UUID changeId;

    @Column(name = "register_float_id", nullable = false, updatable = false)
    private UUID registerFloatId;

    @Column(name = "register_id", length = 100, nullable = false, updatable = false)
    private String registerId;

    /** The register's location after this change; for a RELOCATION, the destination. */
    @Column(name = "location_id", nullable = false, updatable = false)
    private UUID locationId;

    /** On a RELOCATION row: the location the register was held at before the move. */
    @Column(name = "previous_location_id", updatable = false)
    private UUID previousLocationId;

    /** On a RELOCATION row: why the register moved. */
    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 20, updatable = false)
    private RegisterFloatRelocationReason reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 10, nullable = false, updatable = false)
    private RegisterFloatChangeKind kind;

    @Column(name = "previous_amount", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal previousAmount;

    @Column(name = "new_amount", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal newAmount;

    /** The bank side of a CHANGE; null for a go-live (opening balance equity) and a reversal. */
    @Column(name = "bank_gl_account_id", updatable = false)
    private UUID bankGlAccountId;

    /**
     * The entry this row posted; for a REVERSAL, the reversal entry. Null only on a RELOCATION of a zero float,
     * which posts nothing.
     */
    @Column(name = "journal_entry_id", updatable = false)
    private UUID journalEntryId;

    /** On a REVERSAL row: the GO_LIVE or CHANGE row it reversed. */
    @Column(name = "reversed_change_id", updatable = false)
    private UUID reversedChangeId;

    /** On a GO_LIVE or CHANGE row: the entry that reversed it, once reversed. */
    @Column(name = "reversal_journal_entry_id")
    private UUID reversalJournalEntryId;

    @Column(name = "effective_date", nullable = false, updatable = false)
    private LocalDate effectiveDate;

    @Column(name = "justification", length = 1000, nullable = false, updatable = false)
    private String justification;

    @Column(name = "override_justification", length = 1000, updatable = false)
    private String overrideJustification;

    @Column(name = "actor", length = 50, nullable = false, updatable = false)
    private String actor;

    @Column(name = "request_id", updatable = false)
    private UUID requestId;

    @Column(name = "request_hash", length = 64, updatable = false)
    private String requestHash;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "modified_at", nullable = false)
    private Instant updatedAt;
}

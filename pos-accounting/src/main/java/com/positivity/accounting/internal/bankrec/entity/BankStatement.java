package com.positivity.accounting.internal.bankrec.entity;

import com.positivity.accounting.internal.bankrec.enums.BankStatementStatus;
import com.positivity.accounting.internal.bankrec.enums.SourceKind;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
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
 * A bank statement header (SPEC-manual-bank-reconciliation §3.1; story S1, #2300): what the bank
 * says the account held at the start and end of a window, and which window its transactions cover.
 * It is the bank's assertion, never a ledger fact, and is immutable once {@code COMMITTED}.
 *
 * <p>The database holds U1 (one COMMITTED statement per account and window) and U2 (COMMITTED
 * windows on one account never overlap). The service checks that answer them with error codes,
 * contiguity (E2), the gap acknowledgement and the account baseline arrive with story S2.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "bank_statement")
public class BankStatement extends TenantScopedEntity {

    private static final String SYSTEM = "SYSTEM";

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "statement_id", nullable = false, columnDefinition = "UUID")
    private UUID statementId;

    @Column(name = "gl_account_id", nullable = false, columnDefinition = "UUID")
    private UUID glAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_kind", length = 16, nullable = false)
    private SourceKind sourceKind;

    /** {@code FILE_IMPORT}: the import id; {@code BANK_FEED}: the feed connection id; else null. */
    @Column(name = "source_ref", columnDefinition = "UUID")
    private UUID sourceRef;

    @Column(name = "connector_code", length = 64)
    private String connectorCode;

    /** The bank's own statement number, when the file carries one. */
    @Column(name = "statement_ref", length = 64)
    private String statementRef;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "opening_balance", precision = 19, scale = 4, nullable = false)
    private BigDecimal openingBalance;

    @Column(name = "closing_balance", precision = 19, scale = 4, nullable = false)
    private BigDecimal closingBalance;

    /** Σ signed amounts of the statement's transactions at commit (E1). */
    @Column(name = "activity_total", precision = 19, scale = 4, nullable = false)
    private BigDecimal activityTotal;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    /** Required when the statement does not continue the previous COMMITTED one (E2, D17); sets the baseline. */
    @Column(name = "gap_acknowledgement", length = 1000)
    private String gapAcknowledgement;

    @Column(name = "gap_acknowledged_by", length = 50)
    private String gapAcknowledgedBy;

    @Column(name = "gap_acknowledged_at")
    private Instant gapAcknowledgedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private BankStatementStatus status = BankStatementStatus.COMMITTED;

    @Column(name = "superseded_by_statement_id", columnDefinition = "UUID")
    private UUID supersededByStatementId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 50, nullable = false, updatable = false)
    private String createdBy;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onPrePersist() {
        if (createdBy == null) {
            createdBy = SecurityContextHelper.isAuthenticated()
                    ? SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM)
                    : SYSTEM;
        }
    }
}

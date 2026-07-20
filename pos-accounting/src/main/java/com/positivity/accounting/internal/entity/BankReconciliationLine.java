package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.enums.BankReconciliationLineStatus;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
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
 * One imported bank statement line (Story F2, issue #965). Parsed from the CSV
 * (date, description, signed amount, reference). Starts {@link
 * BankReconciliationLineStatus#UNMATCHED}; a match sets it {@link
 * BankReconciliationLineStatus#MATCHED} and stamps {@link #matchId}, grouping the
 * statement lines and GL lines that reconcile together.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(exclude = {"reconciliation"})
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "bank_reconciliation_line",
        indexes = {
            @Index(name = "idx_bank_reconciliation_line_recon", columnList = "reconciliation_id"),
            @Index(name = "idx_bank_reconciliation_line_match", columnList = "match_id")
        })
public class BankReconciliationLine {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "line_id", nullable = false, columnDefinition = "UUID")
    private UUID lineId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reconciliation_id", nullable = false)
    private BankReconciliation reconciliation;

    @Column(name = "line_number", nullable = false)
    private Integer lineNumber;

    @Column(name = "line_date", nullable = false)
    private LocalDate lineDate;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal amount;

    @Column(name = "reference", length = 255)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private BankReconciliationLineStatus status = BankReconciliationLineStatus.UNMATCHED;

    /** Non-null when MATCHED; groups the statement + GL lines of one match set. */
    @Column(name = "match_id", columnDefinition = "UUID")
    private UUID matchId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    public UUID getReconciliationId() {
        return reconciliation != null ? reconciliation.getReconciliationId() : null;
    }
}

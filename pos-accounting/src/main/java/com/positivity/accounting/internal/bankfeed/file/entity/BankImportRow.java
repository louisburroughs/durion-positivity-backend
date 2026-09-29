package com.positivity.accounting.internal.bankfeed.file.entity;

import com.positivity.accounting.internal.bankfeed.file.enums.BankImportRowStatus;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One parsed row of a statement-file import (SPEC-manual-bank-reconciliation §3.3; story S1,
 * #2300). A row is corrected by editing values or skipped with a reason; the original
 * {@link #rawValues} are never rewritten; the parsed columns hold the row's effective values (the
 * correction applied), {@link #correctedValues} what the preparer changed (story S3, #2302).
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "bank_import_row")
public class BankImportRow extends TenantScopedEntity {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "row_id", nullable = false, columnDefinition = "UUID")
    private UUID rowId;

    @Column(name = "import_id", nullable = false, columnDefinition = "UUID")
    private UUID importId;

    @Column(name = "row_number", nullable = false)
    private Integer rowNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_values", nullable = false)
    private Map<String, Object> rawValues;

    @Column(name = "transaction_date")
    private LocalDate transactionDate;

    @Column(name = "signed_amount", precision = 19, scale = 4)
    private BigDecimal signedAmount;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "reference", length = 255)
    private String reference;

    @Column(name = "check_number", length = 32)
    private String checkNumber;

    @Column(name = "source_transaction_id", length = 128)
    private String sourceTransactionId;

    @Column(name = "fingerprint", length = 64)
    private String fingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "row_status", length = 24, nullable = false)
    private BankImportRowStatus rowStatus;

    @Column(name = "rejection_code", length = 64)
    private String rejectionCode;

    @Column(name = "rejection_detail", length = 1000)
    private String rejectionDetail;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "corrected_values")
    private Map<String, Object> correctedValues;

    @Column(name = "corrected_by", length = 50)
    private String correctedBy;

    @Column(name = "corrected_at")
    private Instant correctedAt;

    /** Set at commit: the bank transaction this row became. */
    @Column(name = "bank_transaction_id", columnDefinition = "UUID")
    private UUID bankTransactionId;

    /** Why the row was skipped (at least 10 characters, D15). */
    @Column(name = "skip_reason", length = 1000)
    private String skipReason;

    /** A human's answer to a fingerprint collision: {@code DISTINCT} or {@code DUPLICATE} (§4.4). */
    @Column(name = "duplicate_decision", length = 16)
    private String duplicateDecision;

    /** The stored transaction this row's fingerprint collides with (R1). */
    @Column(name = "duplicate_of_bank_transaction_id", columnDefinition = "UUID")
    private UUID duplicateOfBankTransactionId;

    /** Another row of the same file this row's fingerprint collides with. */
    @Column(name = "duplicate_of_row_number")
    private Integer duplicateOfRowNumber;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}

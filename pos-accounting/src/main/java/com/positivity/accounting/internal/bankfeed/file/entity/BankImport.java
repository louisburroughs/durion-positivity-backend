package com.positivity.accounting.internal.bankfeed.file.entity;

import com.positivity.accounting.internal.bankfeed.file.enums.BankImportStatus;
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
import jakarta.persistence.Version;
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
 * A statement-file import session (SPEC-manual-bank-reconciliation §3.3; story S1, #2300): the
 * file adapter's own staging aggregate. Nothing here is accounting truth until {@code COMMITTED},
 * when the adapter hands the parsed rows to the reconciliation core's intake. The lifecycle
 * (upload, parse, mapping, preview, correction, commit, discard) arrives with story S3.
 *
 * <p>The raw bytes live in {@link BankImportFile}; the parsed rows in {@link BankImportRow}.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "bank_import")
public class BankImport extends TenantScopedEntity {

    private static final String SYSTEM = "SYSTEM";

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "import_id", nullable = false, columnDefinition = "UUID")
    private UUID importId;

    /** Caller-generated UUIDv7 for idempotent creation (§6.3). */
    @Column(name = "request_id", columnDefinition = "UUID")
    private UUID requestId;

    @Column(name = "gl_account_id", nullable = false, columnDefinition = "UUID")
    private UUID glAccountId;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    /** {@code CSV} now; {@code OFX}, {@code QFX}, {@code CAMT053} later. Chooses the parser; invisible to the core. */
    @Column(name = "format_code", length = 16, nullable = false)
    private String formatCode;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "file_sha256", length = 64, nullable = false)
    private String fileSha256;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "column_mapping")
    private Map<String, Object> columnMapping;

    /** {@code SIGNED_AMOUNT}, {@code SIGNED_AMOUNT_INVERTED} or {@code DEBIT_CREDIT_COLUMNS}. */
    @Column(name = "sign_convention", length = 32)
    private String signConvention;

    @Column(name = "date_format", length = 32)
    private String dateFormat;

    @Column(name = "decimal_format", length = 16)
    private String decimalFormat;

    @Column(name = "encoding", length = 32)
    private String encoding;

    @Column(name = "delimiter", length = 4)
    private String delimiter;

    @Column(name = "statement_start_date")
    private LocalDate statementStartDate;

    @Column(name = "statement_end_date")
    private LocalDate statementEndDate;

    @Column(name = "opening_balance", precision = 19, scale = 4)
    private BigDecimal openingBalance;

    @Column(name = "closing_balance", precision = 19, scale = 4)
    private BigDecimal closingBalance;

    @Column(name = "statement_ref", length = 64)
    private String statementRef;

    @Column(name = "row_count")
    private Integer rowCount;

    @Column(name = "accepted_count")
    private Integer acceptedCount;

    @Column(name = "rejected_count")
    private Integer rejectedCount;

    @Column(name = "possible_duplicate_count")
    private Integer possibleDuplicateCount;

    @Column(name = "skipped_count")
    private Integer skippedCount;

    @Column(name = "out_of_window_count")
    private Integer outOfWindowCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private BankImportStatus status = BankImportStatus.UPLOADED;

    /** Set at commit. */
    @Column(name = "statement_id", columnDefinition = "UUID")
    private UUID statementId;

    /** Set at commit when the reconciliation is started in the same transaction. */
    @Column(name = "reconciliation_id", columnDefinition = "UUID")
    private UUID reconciliationId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 50, nullable = false, updatable = false)
    private String createdBy;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "committed_at")
    private Instant committedAt;

    @Column(name = "committed_by", length = 50)
    private String committedBy;

    @Column(name = "discarded_at")
    private Instant discardedAt;

    @Column(name = "discarded_by", length = 50)
    private String discardedBy;

    @Column(name = "discard_reason", length = 1000)
    private String discardReason;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    void onPrePersist() {
        if (createdBy == null) {
            createdBy = SecurityContextHelper.isAuthenticated()
                    ? SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM)
                    : SYSTEM;
        }
    }
}

package com.positivity.accounting.internal.billintake.entity;

import com.positivity.accounting.internal.billintake.enums.BillExtractionOutcome;
import com.positivity.accounting.internal.billintake.enums.BillIntakeChannel;
import com.positivity.accounting.internal.billintake.enums.BillIntakeStatus;
import com.positivity.accounting.internal.billintake.enums.BillVendorResolution;
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
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
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
 * A bill draft a person checks before a bill exists (CAP:550 S25, #2518; ADR-0070 Decision 1;
 * SPEC-accounting-workspace 4.8, 9.2). Nothing here is accounts payable: no AP query reads a draft, and the bill
 * itself is written only by {@code BillIntakePort}, when a draft is confirmed (or, for a machine document that names
 * a vendor, straight from the fact).
 *
 * <p>Part A of the story writes the {@code EDI} channel: a supplier invoice fact that names no vendor becomes a draft
 * in {@code READY_FOR_REVIEW}, its fact values {@code READ} and its vendor {@code CHECK_THIS}. The upload and import
 * channels, and the commands that edit, confirm and discard a draft, come with the later parts.
 *
 * <p>{@link #readBack} holds the reading field by field, {@code {value, state}} each; its values are text and numbers
 * only (ADR-0072) and are never logged. {@link #intakeReference} is the business reference that messages and logs
 * use ({@code BI-YYYYMM-nnnnnnn}); the id is for routing only (ADR-0064).
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@ToString(onlyExplicitlyIncluded = true)
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "bill_intake_item")
public class BillIntakeItem extends TenantScopedEntity {

    @EqualsAndHashCode.Include
    @ToString.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "intake_id", nullable = false, columnDefinition = "UUID")
    private UUID intakeId;

    /** {@code BI-YYYYMM-nnnnnnn}, from the tenant's own counter; unique per tenant. */
    @ToString.Include
    @Column(name = "intake_reference", length = 32, nullable = false, updatable = false)
    private String intakeReference;

    @ToString.Include
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", length = 10, nullable = false, updatable = false)
    private BillIntakeChannel channel;

    @ToString.Include
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private BillIntakeStatus status;

    @Column(name = "source_file_id", columnDefinition = "UUID")
    private UUID sourceFileId;

    /** The draft this one was split from, when a spreadsheet held several invoices. */
    @Column(name = "split_of", columnDefinition = "UUID")
    private UUID splitOf;

    @Column(name = "row_from")
    private Integer rowFrom;

    @Column(name = "row_to")
    private Integer rowTo;

    @Column(name = "mapping_required", nullable = false)
    private boolean mappingRequired;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_columns")
    private List<String> sourceColumns;

    @Column(name = "rejection_reason", length = 40)
    private String rejectionReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "extraction_outcome", length = 20)
    private BillExtractionOutcome extractionOutcome;

    /** Why a reading was not made, as the worker reports it (S26); text only, never logged by value. */
    @Column(name = "extraction_reason", columnDefinition = "TEXT")
    private String extractionReason;

    /** The provider that read the document (S26). */
    @Column(name = "extraction_provider", columnDefinition = "TEXT")
    private String extractionProvider;

    @Enumerated(EnumType.STRING)
    @Column(name = "vendor_resolution", length = 20)
    private BillVendorResolution vendorResolution;

    /** The reading, field by field: {@code {value, state}} each. Text and numbers only; never logged. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "read_back")
    private Map<String, Object> readBack;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "row_errors")
    private List<Map<String, Object>> rowErrors;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "possible_duplicates")
    private List<Map<String, Object>> possibleDuplicates;

    @Column(name = "confirmed_bill_id", columnDefinition = "UUID")
    private UUID confirmedBillId;

    @Column(name = "uploaded_by", length = 50, nullable = false, updatable = false)
    private String uploadedBy;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    @Column(name = "confirmed_by", length = 50)
    private String confirmedBy;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "discarded_by", length = 50)
    private String discardedBy;

    @Column(name = "discarded_at")
    private Instant discardedAt;

    @Column(name = "discard_reason", length = 1000)
    private String discardReason;

    /** Caller-generated id that makes an upload or import replayable; unique per tenant and channel. */
    @Column(name = "request_id", columnDefinition = "UUID")
    private UUID requestId;

    @Column(name = "last_extraction_failed", nullable = false)
    private boolean lastExtractionFailed;

    @Column(name = "edited", nullable = false)
    private boolean edited;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}

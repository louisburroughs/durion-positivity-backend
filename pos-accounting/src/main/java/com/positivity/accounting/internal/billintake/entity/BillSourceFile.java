package com.positivity.accounting.internal.billintake.entity;

import com.positivity.accounting.internal.billintake.enums.BillSourceFileState;
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
import java.time.Instant;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * The stored original of an upload or import, or a derived page of it (CAP:550 S25, #2518; ADR-0070 Decision 8). Its
 * bytes live in {@link BillSourceFileContent}, so a purge deletes those and keeps this row. The original name is
 * metadata for the person who uploaded it; it is never logged (ADR-0072), only the sha-256, size and content type are.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@ToString(onlyExplicitlyIncluded = true)
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "bill_source_file")
public class BillSourceFile extends TenantScopedEntity {

    @EqualsAndHashCode.Include
    @ToString.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "file_id", nullable = false, columnDefinition = "UUID")
    private UUID fileId;

    /** The original this is a derived page of; null for an original. */
    @Column(name = "derived_from", columnDefinition = "UUID")
    private UUID derivedFrom;

    @Column(name = "page_number")
    private Integer pageNumber;

    /** Pages or frames the inspection counted; null before inspection and for a derived page. */
    @Column(name = "page_count")
    private Integer pageCount;

    @ToString.Include
    @Column(name = "sha256", length = 64, nullable = false, updatable = false)
    private String sha256;

    @Column(name = "content_type", length = 100, nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "original_name", length = 255)
    private String originalName;

    @ToString.Include
    @Enumerated(EnumType.STRING)
    @Column(name = "state", length = 16, nullable = false)
    private BillSourceFileState state;

    @Column(name = "retention_until")
    private Instant retentionUntil;

    @Column(name = "legal_hold", nullable = false)
    private boolean legalHold;

    @Column(name = "legal_hold_reason", length = 1000)
    private String legalHoldReason;

    @Column(name = "created_by", length = 50, nullable = false, updatable = false)
    private String createdBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "purged_at")
    private Instant purgedAt;
}

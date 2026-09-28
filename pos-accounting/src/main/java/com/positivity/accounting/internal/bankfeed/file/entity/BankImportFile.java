package com.positivity.accounting.internal.bankfeed.file.entity;

import com.positivity.shared.id.AssignedIdentifier;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * The raw bytes of an imported statement file (SPEC-manual-bank-reconciliation §3.3, §6.4, D13;
 * story S1, #2300), kept apart from {@link BankImport} so the metadata row outlives them: a
 * per-tenant retention job deletes this row after {@link #retentionUntil} (story S3). Encrypted at
 * rest by the database; no application cipher column.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@ToString(exclude = "fileBytes")
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "bank_import_file")
public class BankImportFile extends TenantScopedEntity {

    @EqualsAndHashCode.Include
    @Id
    @AssignedIdentifier("the owning bank_import's own UUIDv7; the file row is 1:1 with that import")
    @Column(name = "import_id", nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID importId;

    @Basic(fetch = FetchType.LAZY)
    @Column(name = "file_bytes", nullable = false)
    private byte[] fileBytes;

    @Column(name = "retention_until")
    private LocalDate retentionUntil;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public BankImportFile(UUID importId) {
        this.importId = importId;
    }
}

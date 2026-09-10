package com.positivity.tax.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tax.common.enums.TaxProviderTransactionStatus;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Provider tax-document lifecycle log (story T6, decision D-T3).
 * <p>
 * One row per source document ({@code reference_id}, unique). The
 * estimate-and-true-up
 * policy records a {@link TaxProviderTransactionStatus#PENDING_COMMIT} row when
 * a commit
 * cannot be applied so a scheduled job can re-attempt it without ever blocking
 * the sale;
 * {@code attempts}/{@code last_error} make the true-up lag observable.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "tax_provider_transaction",
        // For the H2 tests only, which build the schema from the entities: it gives ON CONFLICT DO
        // NOTHING a key to conflict on. On Postgres the Flyway baseline owns the key, and there it is
        // the tenant-scoped (tenant_id, reference_id) index (ADR-0062); the two are not meant to match.
        uniqueConstraints =
                @UniqueConstraint(name = "ux_tax_provider_transaction_reference", columnNames = "reference_id"))
public class TaxProviderTransaction extends TenantScopedEntity {

    /** UUID v7 primary key (ADR-0013). */
    @Id
    @UUIDv7Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Source document code / idempotency key (e.g. the invoice id). */
    @Column(name = "reference_id", nullable = false, updatable = false)
    private UUID referenceId;

    /**
     * Source transaction type label (e.g. {@code INVOICE}); free text for
     * flexibility.
     */
    @Column(name = "reference_type", length = 32)
    private String referenceType;

    /** Provider that owns this document (e.g. {@code TEST_MODE}). */
    @Column(name = "provider", nullable = false, length = 64)
    private String provider;

    /** Lifecycle status. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TaxProviderTransactionStatus status;

    /** Provider transaction id once known; {@code null} in test mode. */
    @Column(name = "external_transaction_id", length = 128)
    private String externalTransactionId;

    /** Number of commit attempts made (initial + re-commit retries). */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    /** Last error recorded on a failed/pending attempt; {@code null} on success. */
    @Column(name = "last_error", length = 1024)
    private String lastError;

    /** Creation timestamp (ADR-0018/0024). */
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Last-modification timestamp (ADR-0018/0024). */
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}

package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
import org.hibernate.annotations.Immutable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Reconciliation record created when invoice posting retry is exhausted.
 * Used for manual reconciliation of failed postings (Story #6 — CAP-251).
 *
 * <p>
 * This entity is intentionally append-only (ADR-0024 @Immutable exemption):
 * reconciliation records are never updated after creation.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Immutable
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "reconciliation_records")
public class ReconciliationRecord {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", columnDefinition = "UUID")
    private UUID reconciliationRecordId;

    @Column(nullable = false, columnDefinition = "UUID")
    private UUID invoiceId;

    @Column(nullable = false)
    private String transactionId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

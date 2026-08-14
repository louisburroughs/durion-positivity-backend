package com.positivity.supplier.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Transactional-outbox row for supplier domain events (ADR-0044 §4).
 *
 * <p>An event exists if and only if the staging transaction that produced it committed, which is
 * what keeps a published chunk from referring to lines that were rolled back. Rows drain in id
 * order (UUIDv7 is time-ordered), so an import's chunk events reach the broker in sequence and the
 * completion event never overtakes the chunks it summarises.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "supplier_event_outbox",
        indexes = {@Index(name = "idx_soutbox_unpublished", columnList = "published_at, id")})
public class SupplierOutboxEventEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "topic", nullable = false, length = 255)
    private String topic;

    @Column(name = "record_key", nullable = false, length = 255)
    private String recordKey;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    @Builder.Default
    private int attempts = 0;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;
}

package com.positivity.securityservice.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.time.TimeSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Immutable pricing snapshot for quote context and final calculated price.
 *
 * Issue: #41
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "pricing_snapshots")
public class PricingSnapshot {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "snapshot_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID snapshotId;

    @Column(name = "timestamp", nullable = false, updatable = false)
    private Instant timestamp;

    @Column(name = "quote_context", columnDefinition = "TEXT", nullable = false, updatable = false)
    private String quoteContext;

    @Column(name = "final_price", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal finalPrice;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (timestamp == null) {
            timestamp = TimeSource.instant();
        }
    }
}

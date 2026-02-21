package com.positivity.inventory.internal.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.positivity.shared.id.UUIDv7Generator;

import java.time.Instant;
import java.util.UUID;

/**
 * Exception queue entry for distributor feed records that cannot be normalized.
 *
 * Issue: CAP-170 (#47)
 */
@Entity
@Table(name = "distributor_feed_exception")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DistributorFeedException {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String distributorId;

    private String distributorSku;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DistributorExceptionReason reason;

    @Column(nullable = false, length = 4000)
    private String rawPayload;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}

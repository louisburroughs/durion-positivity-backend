package com.positivity.inventory.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.positivity.shared.id.UUIDv7Id;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Normalized distributor inventory state keyed by distributor and SKU.
 *
 * Issue: CAP-170 (#47)
 */
@Entity
@Table(name = "distributor_normalized_inventory", uniqueConstraints = @UniqueConstraint(columnNames = {
        "distributor_id", "distributor_sku" }))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class DistributorNormalizedInventory {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID id;

    @Column(nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private String distributorId;

    @Column(nullable = false)
    private String distributorSku;

    @Column(nullable = false)
    private Integer quantityAvailable;

    private Integer leadTimeDaysMin;

    private Integer leadTimeDaysMax;

    private String shipFromRegionCode;

    @Column(nullable = false)
    private String normalizationPolicyVersion;

    private String rawLeadTime;

    private String rawShipFromRegion;

    /**
     * Timestamp when the adjustment was created.
     */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Timestamp when the adjustment was last updated.
     */
    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

}

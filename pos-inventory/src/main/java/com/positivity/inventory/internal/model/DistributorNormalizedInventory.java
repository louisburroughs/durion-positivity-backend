package com.positivity.inventory.internal.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

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
public class DistributorNormalizedInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    @Column(nullable = false)
    private Instant lastUpdatedAt;
}

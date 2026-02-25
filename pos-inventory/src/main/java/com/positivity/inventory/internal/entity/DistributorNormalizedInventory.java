package com.positivity.inventory.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
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
 * Normalized distributor inventory state keyed by distributor and SKU.
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

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}

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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Normalized manufacturer availability snapshot.
 *
 * Issue: CAP-170 (#46)
 */
@Entity
@Table(name = "normalized_availability", uniqueConstraints = @UniqueConstraint(columnNames = { "product_id",
        "manufacturer_id", "as_of" }))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NormalizedAvailability {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private String manufacturerId;

    @Column(nullable = false)
    private String manufacturerPartNumber;

    @Column(nullable = false)
    private Integer availableQty;

    @Column(nullable = false)
    private String uom;

    private BigDecimal unitPriceAmount;

    private String unitPriceCurrency;

    private Integer leadTimeDaysMin;

    private Integer leadTimeDaysMax;

    private Integer minOrderQty;

    @Column(nullable = false)
    private Integer packSize;

    private String sourceLocationCode;

    @Column(nullable = false)
    private Instant asOf;

    @Column(nullable = false)
    private Instant receivedAt;

    @Column(nullable = false)
    private Integer schemaVersion;
}

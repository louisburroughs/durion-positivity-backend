package com.positivity.inventory.internal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Canonical manufacturer feed item payload for feed processing.
 *
 * Issue: CAP-170 (#46)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManufacturerFeedItemDto {
    private UUID productId;
    private String manufacturerId;
    private String manufacturerPartNumber;
    private Integer availableQty;
    private String uom;
    private BigDecimal unitPriceAmount;
    private String unitPriceCurrency;
    private Integer leadTimeDaysMin;
    private Integer leadTimeDaysMax;
    private Integer minOrderQty;
    private Integer packSize;
    private String sourceLocationCode;
    private Instant asOf;
}

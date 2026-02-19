package com.positivity.catalog.internal.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UomConversionDto {
    private UUID id;
    private String fromUomCode;
    private String toUomCode;
    private BigDecimal conversionFactor;
    private boolean active;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
}

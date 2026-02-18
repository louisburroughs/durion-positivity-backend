package com.positivity.catalog.internal.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Data;

@Data
public class ProductMsrpDto {
    private UUID msrpId;
    private UUID productId;
    private String amount;
    private String currency;
    private LocalDate effectiveStartDate;
    private LocalDate effectiveEndDate;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private UUID updatedBy;
    private Long version;
}

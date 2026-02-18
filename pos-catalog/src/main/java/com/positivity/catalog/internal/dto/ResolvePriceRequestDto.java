package com.positivity.catalog.internal.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Data;

@Data
public class ResolvePriceRequestDto {

    @NotNull
    private UUID productId;

    private UUID priceBookId;

    private UUID locationId;

    private String customerTier;

    private String currency;

    private LocalDate asOf;
}

package com.positivity.price.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "A stored hourly labor rate.")
public class LaborRateResponse {

    @Schema(description = "Rate identifier")
    private UUID id;

    @Schema(description = "Location; null = platform default")
    private UUID locationId;

    @Schema(description = "Operation category; null = every category")
    private String operationCategory;

    @Schema(description = "ISO 4217 currency", example = "USD")
    private String currency;

    @Schema(description = "Hourly rate", example = "125.0000")
    private BigDecimal hourlyRate;

    @Schema(description = "Start of the effective window, inclusive")
    private Instant effectiveFrom;

    @Schema(description = "End of the effective window, exclusive; null = open-ended")
    private Instant effectiveTo;
}

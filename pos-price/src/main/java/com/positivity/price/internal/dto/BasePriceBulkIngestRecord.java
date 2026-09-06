package com.positivity.price.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Single base-price record within a bulk ingest payload")
public class BasePriceBulkIngestRecord {

    @Schema(
            description = "Product identifier the base price applies to",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotBlank
    private String productId;

    @Schema(description = "Manufacturer suggested retail price", example = "125.50", requiredMode = REQUIRED)
    @NotBlank
    private String msrp;

    @Schema(description = "ISO-4217 currency code", example = "USD", requiredMode = REQUIRED)
    @NotBlank
    @Size(min = 3, max = 3, message = "currency must be an ISO-4217 3-character code")
    private String currency;

    @Schema(
            description = "Instant the base price becomes effective, ISO-8601",
            example = "2026-03-01T00:00:00Z",
            requiredMode = REQUIRED)
    @NotBlank
    private String effectiveFrom;
}

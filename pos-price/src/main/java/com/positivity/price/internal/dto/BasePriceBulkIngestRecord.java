package com.positivity.price.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Single base-price record within a bulk ingest payload")
public class BasePriceBulkIngestRecord {

    @Schema(description = "Product identifier the base price applies to", example = "P001", requiredMode = REQUIRED)
    @NotBlank
    private String productId;

    @Schema(description = "Manufacturer suggested retail price", example = "125.50", requiredMode = REQUIRED)
    @NotBlank
    private String msrp;

    @Schema(description = "ISO-4217 currency code", example = "USD", requiredMode = REQUIRED)
    @NotBlank
    @Size(min = 3, max = 3, message = "currency must be an ISO-4217 3-character code")
    private String currency;

    @Schema(description = "Date the base price becomes effective", example = "2026-03-01", requiredMode = REQUIRED)
    @NotBlank
    private String effectiveFrom;
}

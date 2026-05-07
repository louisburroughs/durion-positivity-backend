package com.positivity.price.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class BasePriceBulkIngestRecord {

    @NotBlank
    private String productId;

    @NotBlank
    private String msrp;

    @NotBlank
    @Size(min = 3, max = 3, message = "currency must be an ISO-4217 3-character code")
    private String currency;

    @NotBlank
    private String effectiveFrom;
}

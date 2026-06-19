package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Resolved price result for a product in a given context")
public class ResolvePriceResponseDto {

    public enum ResolvePriceSource {
        PRICE_BOOK_RULE,
        MSRP,
        UNAVAILABLE
    }

    @Schema(
            description = "Resolved price amount (null when source is UNAVAILABLE)",
            example = "149.99",
            requiredMode = NOT_REQUIRED)
    private String resolvedAmount;

    @Schema(
            description = "ISO currency code of the resolved amount (null when source is UNAVAILABLE)",
            example = "USD",
            requiredMode = NOT_REQUIRED)
    private String currency;

    @Schema(
            description = "Origin of the resolved price",
            example = "PRICE_BOOK_RULE",
            requiredMode = REQUIRED)
    private ResolvePriceSource source;

    @Schema(
            description = "Identifier of the price book rule that produced the amount, when sourced from a price book",
            example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535",
            requiredMode = NOT_REQUIRED)
    private UUID sourceRuleId;

    @Schema(
            description = "Explanation for a fallback or unavailable result",
            example = "No active price book rule; MSRP used",
            requiredMode = NOT_REQUIRED)
    private String fallbackReason;
}

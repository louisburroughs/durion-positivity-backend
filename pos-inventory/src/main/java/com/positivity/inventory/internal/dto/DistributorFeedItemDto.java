package com.positivity.inventory.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Canonical distributor feed item payload for normalization.
 *
 * Issue: CAP-170 (#47)
 */
@Schema(description = "Canonical distributor feed item payload submitted for normalization")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DistributorFeedItemDto {

    @Schema(
            description = "Internal product identifier the feed item maps to",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID productId;

    @Schema(
            description = "Identifier of the distributor supplying the feed",
            example = "DIST-001",
            requiredMode = REQUIRED)
    @NotBlank
    private String distributorId;

    @Schema(
            description = "Distributor-specific stock-keeping unit for the product",
            example = "SKU-10042",
            requiredMode = REQUIRED)
    @NotBlank
    private String distributorSku;

    @Schema(
            description = "Quantity reported as available by the distributor",
            example = "120",
            requiredMode = REQUIRED)
    @NotNull
    @PositiveOrZero
    private Integer quantityAvailable;

    @Schema(
            description = "Raw, un-normalized lead time text as supplied by the distributor",
            example = "3-5 business days",
            requiredMode = NOT_REQUIRED)
    private String rawLeadTime;

    @Schema(
            description = "Raw, un-normalized ship-from region text as supplied by the distributor",
            example = "US-WEST",
            requiredMode = NOT_REQUIRED)
    private String rawShipFromRegion;
}

package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.catalog.internal.entity.PriceBookScope;
import com.positivity.catalog.internal.entity.PriceBookStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Request payload for creating a price book")
public class PriceBookCreateRequestDto {

    @NotNull
    @Schema(description = "Price book name", example = "West Region Pricing", requiredMode = REQUIRED)
    private String name;

    @NotNull
    @Schema(
            description = "Scope the price book applies to",
            example = "LOCATION",
            implementation = PriceBookScope.class,
            requiredMode = REQUIRED)
    private PriceBookScope scope;

    @Schema(
            description = "Identifier of the scoped entity (location or customer tier)",
            example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535",
            requiredMode = NOT_REQUIRED)
    private UUID scopeId;

    @Schema(
            description = "Whether this is the default price book for the scope",
            example = "false",
            requiredMode = NOT_REQUIRED)
    private Boolean isDefault;

    @Schema(
            description = "Initial status of the price book",
            example = "ACTIVE",
            implementation = PriceBookStatus.class,
            requiredMode = NOT_REQUIRED)
    private PriceBookStatus status;
}

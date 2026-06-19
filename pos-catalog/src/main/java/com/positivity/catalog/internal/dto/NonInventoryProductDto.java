package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Non-inventory product")
public class NonInventoryProductDto {

    @Schema(
            description = "Non-inventory identifier",
            example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535",
            requiredMode = REQUIRED)
    private UUID id;

    @Schema(description = "Name", example = "Shop Supplies Fee", requiredMode = REQUIRED)
    private String name;

    @Schema(
            description = "Long description",
            example = "Miscellaneous consumable shop supplies",
            requiredMode = NOT_REQUIRED)
    private String longDescription;

    @Schema(description = "Short description", example = "Shop supplies", requiredMode = NOT_REQUIRED)
    private String shortDescription;
}

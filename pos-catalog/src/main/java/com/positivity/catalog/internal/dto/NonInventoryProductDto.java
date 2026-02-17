package com.positivity.catalog.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Non-inventory product")
public class NonInventoryProductDto {

    @Schema(description = "Non-inventory identifier", example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535")
    private UUID id;

    @Schema(description = "Name")
    private String name;

    @Schema(description = "Long description")
    private String longDescription;

    @Schema(description = "Short description")
    private String shortDescription;
}

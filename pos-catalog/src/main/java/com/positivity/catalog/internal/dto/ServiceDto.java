package com.positivity.catalog.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Catalog service item")
public class ServiceDto {

    @Schema(description = "Service identifier", example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535")
    private UUID id;

    @Schema(description = "Service name", example = "Standard installation")
    private String name;

    @Schema(description = "Long service description")
    private String longDescription;

    @Schema(description = "Short service description")
    private String shortDescription;
}

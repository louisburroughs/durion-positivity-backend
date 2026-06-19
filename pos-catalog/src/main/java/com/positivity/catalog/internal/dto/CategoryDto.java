package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Product category")
public class CategoryDto {

    @NotNull
    @Schema(
            description = "Category identifier",
            example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535",
            requiredMode = REQUIRED)
    private UUID id;

    @NotNull
    @Schema(description = "Category name", example = "Tools", requiredMode = REQUIRED)
    private String name;
}
